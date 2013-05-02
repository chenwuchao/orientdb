/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.orient.core.storage.impl.local.paginated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OAddNewPageRecord;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OEndAtomicPageUpdateRecord;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OLogSequenceNumber;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OSetPageDataRecord;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OStartAtomicPageUpdateRecord;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWriteAheadLog;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.core.version.OVersionFactory;

/**
 * @author Andrey Lomakin
 * @since 19.03.13
 */
public class OLocalPage {
  private static final int     VERSION_SIZE               = OVersionFactory.instance().getVersionSize();

  private static final int     MAGIC_NUMBER_OFFSET        = 0;
  private static final int     CRC32_OFFSET               = MAGIC_NUMBER_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int     WAL_SEGMENT_OFFSET         = CRC32_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int     WAL_POSITION_OFFSET        = WAL_SEGMENT_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int     NEXT_PAGE_OFFSET           = WAL_POSITION_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int     PREV_PAGE_OFFSET           = NEXT_PAGE_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int     FREELIST_HEADER_OFFSET     = PREV_PAGE_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int     FREE_POSITION_OFFSET       = FREELIST_HEADER_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int     FREE_SPACE_COUNTER_OFFSET  = FREE_POSITION_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int     ENTRIES_COUNT_OFFSET       = FREE_SPACE_COUNTER_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int     PAGE_INDEXES_LENGTH_OFFSET = ENTRIES_COUNT_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int     PAGE_INDEXES_OFFSET        = PAGE_INDEXES_LENGTH_OFFSET + OIntegerSerializer.INT_SIZE;

  private static final int     INDEX_ITEM_SIZE            = OIntegerSerializer.INT_SIZE + VERSION_SIZE;
  private static final int     MARKED_AS_DELETED_FLAG     = 1 << 16;
  private static final int     POSITION_MASK              = 0xFFFF;
  public static final int      PAGE_SIZE                  = OGlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;

  public static final int      MAX_ENTRY_SIZE             = PAGE_SIZE - PAGE_INDEXES_OFFSET - INDEX_ITEM_SIZE;

  public static final int      MAX_RECORD_SIZE            = MAX_ENTRY_SIZE - 2 * OIntegerSerializer.INT_SIZE;

  private final long           pagePointer;
  private final ODirectMemory  directMemory               = ODirectMemoryFactory.INSTANCE.directMemory();

  private final OWriteAheadLog walLog;
  private final long           pageIndex;
  private final String         fileName;

  public OLocalPage(long pagePointer, boolean newPage, OWriteAheadLog walLog, long pageIndex, String fileName) throws IOException {
    this.walLog = walLog;
    this.pagePointer = pagePointer;
    this.pageIndex = pageIndex;
    this.fileName = fileName;

    if (newPage) {
      startAtomicUpdate();
      try {
        logAddNewPage();

        setLongValue(NEXT_PAGE_OFFSET, -1);
        setLongValue(PREV_PAGE_OFFSET, -1);

        setIntValue(FREE_POSITION_OFFSET, PAGE_SIZE);
        setIntValue(FREE_SPACE_COUNTER_OFFSET, PAGE_SIZE - PAGE_INDEXES_OFFSET);
      } finally {
        endAtomicUpdate();
      }
    }
  }

  public OLogSequenceNumber getLsn() {
    int segment = OIntegerSerializer.INSTANCE.deserializeFromDirectMemory(directMemory, pagePointer + WAL_SEGMENT_OFFSET);
    long position = OLongSerializer.INSTANCE.deserializeFromDirectMemory(directMemory, pagePointer + WAL_POSITION_OFFSET);

    return new OLogSequenceNumber(segment, position);
  }

  private void setLsn(OLogSequenceNumber lsn) {
    OIntegerSerializer.INSTANCE.serializeInDirectMemory(lsn.getSegment(), directMemory, pagePointer + WAL_SEGMENT_OFFSET);
    OLongSerializer.INSTANCE.serializeInDirectMemory(lsn.getPosition(), directMemory, pagePointer + WAL_POSITION_OFFSET);
  }

  public int appendRecord(ORecordVersion recordVersion, byte[] record) throws IOException {
    startAtomicUpdate();
    try {
      int freePosition = getIntValue(FREE_POSITION_OFFSET);
      int indexesLength = getIntValue(PAGE_INDEXES_LENGTH_OFFSET);

      int lastEntryIndexPosition = PAGE_INDEXES_OFFSET + indexesLength * INDEX_ITEM_SIZE;

      int entrySize = record.length + 2 * OIntegerSerializer.INT_SIZE;
      int freeListHeader = getIntValue(FREELIST_HEADER_OFFSET);

      if (!checkSpace(entrySize, freeListHeader))
        return -1;

      if (freeListHeader > 0) {
        if (freePosition - entrySize < lastEntryIndexPosition)
          doDefragmentation();
      } else {
        if (freePosition - entrySize < lastEntryIndexPosition + INDEX_ITEM_SIZE)
          doDefragmentation();
      }

      freePosition = getIntValue(FREE_POSITION_OFFSET);
      freePosition -= entrySize;
      int entryIndex;

      if (freeListHeader > 0) {
        entryIndex = freeListHeader - 1;

        final int tombstonePointer = getIntValue(PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * entryIndex);

        int nextEntryPosition = tombstonePointer & POSITION_MASK;
        if (nextEntryPosition > 0)
          setIntValue(FREELIST_HEADER_OFFSET, nextEntryPosition);
        else
          setIntValue(FREELIST_HEADER_OFFSET, 0);

        setIntValue(FREE_SPACE_COUNTER_OFFSET, getFreeSpace() - entrySize);

        int entryIndexPosition = PAGE_INDEXES_OFFSET + entryIndex * INDEX_ITEM_SIZE;
        setIntValue(entryIndexPosition, freePosition);

        byte[] serializedVersion = getBinaryValue(entryIndexPosition + OIntegerSerializer.INT_SIZE, OVersionFactory.instance()
            .getVersionSize());

        ORecordVersion existingRecordVersion = OVersionFactory.instance().createVersion();
        existingRecordVersion.getSerializer().fastReadFrom(serializedVersion, 0, existingRecordVersion);

        if (existingRecordVersion.compareTo(recordVersion) < 0) {
          recordVersion.getSerializer().fastWriteTo(serializedVersion, 0, recordVersion);
          setBinaryValue(entryIndexPosition + OIntegerSerializer.INT_SIZE, serializedVersion);
        } else {
          existingRecordVersion.increment();
          existingRecordVersion.getSerializer().fastWriteTo(serializedVersion, 0, existingRecordVersion);
          setBinaryValue(entryIndexPosition + OIntegerSerializer.INT_SIZE, serializedVersion);
        }

      } else {
        entryIndex = indexesLength;

        setIntValue(PAGE_INDEXES_LENGTH_OFFSET, indexesLength + 1);
        setIntValue(FREE_SPACE_COUNTER_OFFSET, getFreeSpace() - entrySize - INDEX_ITEM_SIZE);

        int entryIndexPosition = PAGE_INDEXES_OFFSET + entryIndex * INDEX_ITEM_SIZE;
        setIntValue(entryIndexPosition, freePosition);

        byte[] serializedVersion = new byte[OVersionFactory.instance().getVersionSize()];
        recordVersion.getSerializer().fastWriteTo(serializedVersion, 0, recordVersion);
        setBinaryValue(entryIndexPosition + OIntegerSerializer.INT_SIZE, serializedVersion);
      }

      int entryPosition = freePosition;
      setIntValue(freePosition, entrySize);
      entryPosition += OIntegerSerializer.INT_SIZE;

      setIntValue(entryPosition, entryIndex);
      entryPosition += OIntegerSerializer.INT_SIZE;

      setBinaryValue(entryPosition, record);

      setIntValue(FREE_POSITION_OFFSET, freePosition);

      incrementEntriesCount();

      return entryIndex;
    } finally {
      endAtomicUpdate();
    }
  }

  public ORecordVersion getRecordVersion(int position) {
    int entryIndexPosition = PAGE_INDEXES_OFFSET + position * INDEX_ITEM_SIZE;
    byte[] serializedVersion = getBinaryValue(entryIndexPosition + OIntegerSerializer.INT_SIZE, OVersionFactory.instance()
        .getVersionSize());

    ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
    recordVersion.getSerializer().fastReadFrom(serializedVersion, 0, recordVersion);

    return recordVersion;
  }

  public boolean isEmpty() {
    return getFreeSpace() == PAGE_SIZE - PAGE_INDEXES_OFFSET;
  }

  private boolean checkSpace(int entrySize, int freeListHeader) {
    if (freeListHeader > 0) {
      if (getFreeSpace() - entrySize < 0)
        return false;
    } else {
      if (getFreeSpace() - entrySize - INDEX_ITEM_SIZE < 0)
        return false;
    }
    return true;
  }

  public boolean deleteRecord(int position) throws IOException {
    startAtomicUpdate();
    try {
      int indexesLength = getIntValue(PAGE_INDEXES_LENGTH_OFFSET);
      if (position >= indexesLength)
        return false;

      int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * position;
      int entryPointer = getIntValue(entryIndexPosition);

      if ((entryPointer & MARKED_AS_DELETED_FLAG) > 0)
        return false;

      int entryPosition = entryPointer & POSITION_MASK;

      int freeListHeader = getIntValue(FREELIST_HEADER_OFFSET);
      if (freeListHeader <= 0)
        setIntValue(entryIndexPosition, MARKED_AS_DELETED_FLAG);
      else
        setIntValue(entryIndexPosition, freeListHeader | MARKED_AS_DELETED_FLAG);

      setIntValue(FREELIST_HEADER_OFFSET, position + 1);

      final int entrySize = getIntValue(entryPosition);
      assert entrySize > 0;

      setIntValue(entryPosition, -entrySize);
      setIntValue(FREE_SPACE_COUNTER_OFFSET, getFreeSpace() + entrySize);

      decrementEntriesCount();

      return true;
    } finally {
      endAtomicUpdate();
    }
  }

  public boolean isDeleted(int position) {
    int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * position;
    int entryPointer = getIntValue(entryIndexPosition);

    return (entryPointer & MARKED_AS_DELETED_FLAG) > 0;
  }

  public long getRecordPointer(int position) {
    int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * position;
    int entryPointer = getIntValue(entryIndexPosition);
    if ((entryPointer & MARKED_AS_DELETED_FLAG) > 0)
      return ODirectMemory.NULL_POINTER;

    int entryPosition = entryPointer & POSITION_MASK;
    return pagePointer + entryPosition + 2 * OIntegerSerializer.INT_SIZE;
  }

  public int getRecordSize(int position) {
    int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * position;
    int entryPointer = getIntValue(entryIndexPosition);
    if ((entryPointer & MARKED_AS_DELETED_FLAG) > 0)
      return -1;

    int entryPosition = entryPointer & POSITION_MASK;
    return getIntValue(entryPosition) - 2 * OIntegerSerializer.INT_SIZE;
  }

  public int findFirstDeletedRecord(int position) {
    int indexesLength = getIntValue(PAGE_INDEXES_LENGTH_OFFSET);
    for (int i = position; i < indexesLength; i++) {
      int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * i;
      int entryPointer = getIntValue(entryIndexPosition);
      if ((entryPointer & MARKED_AS_DELETED_FLAG) > 0)
        return i;
    }

    return -1;
  }

  public int findFirstRecord(int position) {
    int indexesLength = getIntValue(PAGE_INDEXES_LENGTH_OFFSET);
    for (int i = position; i < indexesLength; i++) {
      int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * i;
      int entryPointer = getIntValue(entryIndexPosition);
      if ((entryPointer & MARKED_AS_DELETED_FLAG) == 0)
        return i;
    }

    return -1;
  }

  public int findLastRecord(int position) {
    int indexesLength = getIntValue(PAGE_INDEXES_LENGTH_OFFSET);

    int endIndex = Math.min(indexesLength - 1, position);
    for (int i = endIndex; i >= 0; i--) {
      int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * i;
      int entryPointer = getIntValue(entryIndexPosition);
      if ((entryPointer & MARKED_AS_DELETED_FLAG) == 0)
        return i;
    }

    return -1;
  }

  public int getFreeSpace() {
    return getIntValue(FREE_SPACE_COUNTER_OFFSET);
  }

  public int getMaxRecordSize() {
    int freeListHeader = getIntValue(FREELIST_HEADER_OFFSET);

    int maxEntrySize;
    if (freeListHeader > 0)
      maxEntrySize = getFreeSpace();
    else
      maxEntrySize = getFreeSpace() - INDEX_ITEM_SIZE;

    return maxEntrySize - 2 * OIntegerSerializer.INT_SIZE;
  }

  public int getRecordsCount() {
    return getIntValue(ENTRIES_COUNT_OFFSET);
  }

  public long getNextPage() {
    return getLongValue(NEXT_PAGE_OFFSET);
  }

  public void setNextPage(long nextPage) throws IOException {
    setLongValue(NEXT_PAGE_OFFSET, nextPage);
  }

  public long getPrevPage() {
    return getLongValue(PREV_PAGE_OFFSET);
  }

  public void setPrevPage(long prevPage) throws IOException {
    setLongValue(PREV_PAGE_OFFSET, prevPage);
  }

  private void incrementEntriesCount() throws IOException {
    setIntValue(ENTRIES_COUNT_OFFSET, getRecordsCount() + 1);
  }

  private void decrementEntriesCount() throws IOException {
    setIntValue(ENTRIES_COUNT_OFFSET, getRecordsCount() - 1);
  }

  private void doDefragmentation() throws IOException {
    int freePosition = getIntValue(FREE_POSITION_OFFSET);

    int currentPosition = freePosition;
    List<Integer> processedPositions = new ArrayList<Integer>();

    while (currentPosition < PAGE_SIZE) {
      int entrySize = getIntValue(currentPosition);

      if (entrySize > 0) {
        int positionIndex = getIntValue(currentPosition + OIntegerSerializer.INT_SIZE);
        processedPositions.add(positionIndex);

        currentPosition += entrySize;
      } else {
        entrySize = -entrySize;
        shiftData(freePosition, freePosition + entrySize, currentPosition - freePosition);
        currentPosition += entrySize;
        freePosition += entrySize;

        shiftPositions(processedPositions, entrySize);
      }
    }

    setIntValue(FREE_POSITION_OFFSET, freePosition);
  }

  private void shiftPositions(List<Integer> processedPositions, int entrySize) throws IOException {
    for (int positionIndex : processedPositions) {
      int entryIndexPosition = PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * positionIndex;
      int entryPosition = getIntValue(entryIndexPosition);
      setIntValue(entryIndexPosition, entryPosition + entrySize);
    }
  }

  private int getIntValue(int pageOffset) {
    return OIntegerSerializer.INSTANCE.deserializeFromDirectMemory(directMemory, pagePointer + pageOffset);
  }

  private long getLongValue(int pageOffset) {
    return OLongSerializer.INSTANCE.deserializeFromDirectMemory(directMemory, pagePointer + pageOffset);
  }

  private byte[] getBinaryValue(int pageOffset, int valLen) {
    return directMemory.get(pagePointer + pageOffset, valLen);
  }

  private void setIntValue(int pageOffset, int value) throws IOException {
    if (walLog == null) {
      OIntegerSerializer.INSTANCE.serializeInDirectMemory(value, directMemory, pagePointer + pageOffset);
    } else {
      final byte[] content = new byte[OIntegerSerializer.INT_SIZE];
      OIntegerSerializer.INSTANCE.serializeNative(value, content, 0);
      walLog.logRecord(new OSetPageDataRecord(content, pageOffset, pageIndex, fileName));
      directMemory.set(pagePointer + pageOffset, content, 0, content.length);
    }

  }

  private void setLongValue(int pageOffset, long value) throws IOException {
    if (walLog == null) {
      OLongSerializer.INSTANCE.serializeInDirectMemory(value, directMemory, pagePointer + pageOffset);
    } else {
      final byte[] content = new byte[OIntegerSerializer.INT_SIZE];
      OLongSerializer.INSTANCE.serializeNative(value, content, 0);
      walLog.logRecord(new OSetPageDataRecord(content, pageOffset, pageIndex, fileName));
      directMemory.set(pageOffset + pagePointer, content, 0, content.length);
    }

  }

  private void setBinaryValue(int pageOffset, byte[] value) throws IOException {
    if (walLog == null) {
      directMemory.set(pagePointer + pageOffset, value, 0, value.length);
    } else {
      walLog.logRecord(new OSetPageDataRecord(value, pageOffset, pageIndex, fileName));
      directMemory.set(pagePointer + pageOffset, value, 0, value.length);
    }

  }

  private void shiftData(int from, int to, int len) throws IOException {
    if (walLog == null) {
      directMemory.copyData(pagePointer + from, pagePointer + to, len);
    } else {
      byte[] content = directMemory.get(pagePointer + from, len);
      walLog.logRecord(new OSetPageDataRecord(content, to, pageIndex, fileName));
      directMemory.copyData(pagePointer + from, pagePointer + to, len);
    }

  }

  private void startAtomicUpdate() throws IOException {
    if (walLog != null)
      walLog.logRecord(new OStartAtomicPageUpdateRecord(pageIndex, fileName));
  }

  private void endAtomicUpdate() throws IOException {
    if (walLog != null)
      setLsn(walLog.logRecord(new OEndAtomicPageUpdateRecord(pageIndex, fileName)));
  }

  private void logAddNewPage() throws IOException {
    if (walLog != null)
      walLog.logRecord(new OAddNewPageRecord(pageIndex, fileName));
  }
}
