/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License").  See License in the project root for license information.
 */

package com.linkedin.kafka.clients.largemessage;

import com.linkedin.kafka.clients.largemessage.errors.InvalidSegmentException;
import com.linkedin.kafka.clients.largemessage.errors.LargeMessageDroppedException;
import com.linkedin.kafka.clients.utils.QueuedMap;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/**
 * The class to hold incomplete messages. This class assumes it has full control over the segment buffered.
 * So once the segment is passed to this class, user should not change the segment anymore.
 * <p>
 * This class is thread safe. We simply use the synchronized method here because large messages are expected
 * to be sparse.
 */
public class LargeMessageBufferPool {
  private static final Logger LOG = LoggerFactory.getLogger(LargeMessageBufferPool.class);
  private final QueuedMap<UUID, LargeMessage> _incompleteMessageMap;
  private final Map<TopicPartition, Set<UUID>> _incompleteMessageByPartition;
  private final LargeMessageOffsetTracker _offsetTracker;
  private final long _bufferCapacity;
  private final long _expirationOffsetGap;
  private final boolean _exceptionOnMessageDropped;
  private long _bufferUsed;


  // Constructor for unit test purpose.
  LargeMessageBufferPool(long bufferCapacity, long expirationOffsetGap, boolean exceptionOnMessaegDropped) {
    _incompleteMessageMap = new QueuedMap<>();
    _incompleteMessageByPartition = new HashMap<>();
    _bufferCapacity = bufferCapacity;
    _expirationOffsetGap = expirationOffsetGap;
    _offsetTracker = new LargeMessageOffsetTracker();
    _bufferUsed = 0L;
    _exceptionOnMessageDropped = exceptionOnMessaegDropped;
  }

  synchronized long bufferUsed() {
    return _bufferUsed;
  }

  synchronized int size() {
    return _incompleteMessageMap.size();
  }

  synchronized LargeMessage.SegmentAddResult tryCompleteMessage(TopicPartition tp, long offset, LargeMessageSegment segment) {
    LargeMessage message = validateSegmentAndGetMessage(tp, segment, offset);

    int segmentSize = segment.payload.remaining();
    if (segmentSize >= _bufferCapacity) {
      throw new InvalidSegmentException("Saw single message segment size = " + segmentSize + ", which is "
                                            + "larger than buffer capacity = " + _bufferCapacity);
    }

    // Check if this segment completes the large message.
    UUID messageId = segment.messageId;
    LargeMessage.SegmentAddResult segmentAddResult = message.addSegment(segment, offset);
    _bufferUsed += segmentAddResult.bytesAdded();
    LOG.trace("Added {} bytes to messageId={}", segmentAddResult.bytesAdded(), messageId);
    if (segmentAddResult.serializedMessage() != null) {
      LOG.debug("Message {} completed.", messageId);
      removeMessage(messageId);
      _offsetTracker.untrackMessage(tp, messageId);
      _incompleteMessageByPartition.get(tp).remove(messageId);
    } else {
      Set<UUID> uuidSetForPartition = _incompleteMessageByPartition.get(tp);
      if (uuidSetForPartition == null) {
        uuidSetForPartition = new HashSet<>();
        _incompleteMessageByPartition.put(tp, uuidSetForPartition);
      }
      uuidSetForPartition.add(messageId);
      _offsetTracker.maybeTrackMessage(tp, messageId, offset);
    }
    evictMessagesForSpace();
    expireSegments(tp, offset);

    return segmentAddResult;
  }

  /**
   * Expire segments that are beyond the expirationOffsetGap.
   */
  private void expireSegments(TopicPartition tp, long offset) {
    for (UUID expiredMessageId : _offsetTracker.expireMessageUntilOffset(tp, offset - _expirationOffsetGap)) {
      removeMessage(expiredMessageId);
      _incompleteMessageByPartition.get(tp).remove(expiredMessageId);
    }
  }

  /**
   * returns the current set of safe offsets.
   * This will *not* clear any stale state.
   * Probably don't use this except for testing purposes.
   * @return the current safe offsets (keyed by TP)
   */
  public synchronized Map<TopicPartition, Long> safeOffsets() {
    return _offsetTracker.safeOffsets();
  }

  public synchronized long safeOffset(TopicPartition tp, long currentOffset) {
    expireSegments(tp, currentOffset);
    return _offsetTracker.safeOffset(tp);
  }

  public synchronized void clear() {
    _incompleteMessageMap.clear();
    _incompleteMessageByPartition.clear();
    _offsetTracker.clear();
    _bufferUsed = 0L;
  }

  public synchronized void clear(TopicPartition tp) {
    Set<UUID> uuidSetForPartition = _incompleteMessageByPartition.get(tp);
    if (uuidSetForPartition != null) {
      for (UUID messageId : uuidSetForPartition) {
        removeMessage(messageId);
        _offsetTracker.untrackMessage(tp, messageId);
      }
      _incompleteMessageByPartition.remove(tp);
      _offsetTracker.clear(tp);
    }
  }

  private void removeMessage(UUID messageId) {
    LargeMessage message = _incompleteMessageMap.remove(messageId);
    if (message != null) {
      _bufferUsed -= message.bufferedSizeInBytes();
    }
  }

  private void evictMessagesForSpace() {
    // When the eldest message is the current message, the message will not be completed. This indicates the buffer
    // capacity is too small to hold even one message.
    while (_bufferUsed > _bufferCapacity) {
      LargeMessage message = evictEldestMessage();
      if (message != null) {
        _offsetTracker.untrackMessage(message.topicPartition(), message.messageId());
        _incompleteMessageByPartition.get(message.topicPartition()).remove(message.messageId());
        if (_exceptionOnMessageDropped) {
          throw new LargeMessageDroppedException("The following large Message is dropped due to buffer full. "
              + message);
        } else {
          LOG.warn("Incomplete message buffer pool is full. Removing the eldest incomplete message." + message);
        }
      } else {
        throw new IllegalStateException("The buffer used is " + _bufferUsed + " even if there is no incomplete "
                                            + "large message.");
      }
    }
  }

  private LargeMessage evictEldestMessage() {
    UUID eldestKey = _incompleteMessageMap.getEldestKey();
    LargeMessage message = null;
    if (eldestKey != null) {
      message = _incompleteMessageMap.get(eldestKey);
      long offsetBeforeRemoval = _offsetTracker.safeOffset(message.topicPartition());
      removeMessage(eldestKey);
      long offsetAfterRemoval = _offsetTracker.safeOffset(message.topicPartition());

      String errMsg = "Large message " + message.toString() + " is evicted. "
          + "Offset of " + message.topicPartition() + " has advanced from " + offsetBeforeRemoval
          + " to " + offsetAfterRemoval;
      LOG.warn(errMsg);
    }
    return message;
  }

  private LargeMessage validateSegmentAndGetMessage(TopicPartition tp, LargeMessageSegment segment, long offset) {
    if (segment.payload == null) {
      throw new InvalidSegmentException("Payload cannot be null");
    }
    segment.payload.rewind();
    long segmentSize = segment.payload.remaining();
    UUID messageId = segment.messageId;
    int messageSizeInBytes = segment.messageSizeInBytes;
    int numberOfSegments = segment.numberOfSegments;
    int seq = segment.sequenceNumber;

    if (messageId == null) {
      throw new InvalidSegmentException("Message Id can not be null");
    }
    if (segmentSize > messageSizeInBytes) {
      throw new InvalidSegmentException("Segment size should not be larger than message size.");
    }

    if (seq < 0 || seq > numberOfSegments - 1) {
      throw new InvalidSegmentException("Sequence number " + seq
          + " should fall between [0," + (numberOfSegments - 1) + "].");
    }

    // Create the incomplete message if needed.
    LargeMessage message = _incompleteMessageMap.get(messageId);
    if (message == null) {
      message = new LargeMessage(tp, messageId, offset, messageSizeInBytes, numberOfSegments);
      _incompleteMessageMap.put(messageId, message);
      LOG.trace("Incomplete message {} is created.", messageId);
    }
    if (message.startingOffset() > offset) {
      throw new InvalidSegmentException("Out of order segment offsets detected.");
    }
    return message;
  }
}
