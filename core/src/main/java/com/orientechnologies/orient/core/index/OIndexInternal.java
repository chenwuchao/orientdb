/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.core.index;

import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.tx.OTransactionIndexChanges;

import java.util.Comparator;

/**
 * Interface to handle index.
 *
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 */
public interface OIndexInternal<T> extends OIndex<T> {

  /**
   * Orders indexes by its index ID.
   */
  Comparator<OIndexInternal> ID_COMPARATOR = new Comparator<OIndexInternal>() {
    @Override
    public int compare(OIndexInternal o1, OIndexInternal o2) {
      return o2.getIndexId() - o1.getIndexId();
    }
  };

  String CONFIG_KEYTYPE            = "keyType";
  String CONFIG_AUTOMATIC          = "automatic";
  String CONFIG_TYPE               = "type";
  String ALGORITHM                 = "algorithm";
  String VALUE_CONTAINER_ALGORITHM = "valueContainerAlgorithm";
  String CONFIG_NAME               = "name";
  String INDEX_DEFINITION          = "indexDefinition";
  String INDEX_DEFINITION_CLASS    = "indexDefinitionClass";
  String INDEX_VERSION             = "indexVersion";
  String METADATA                  = "metadata";

  Object getCollatingValue(final Object key);

  /**
   * Loads the index giving the configuration.
   *
   * @param iConfig ODocument instance containing the configuration
   */
  boolean loadFromConfiguration(ODocument iConfig);

  /**
   * Saves the index configuration to disk.
   *
   * @return The configuration as ODocument instance
   *
   * @see #getConfiguration()
   */
  ODocument updateConfiguration();

  /**
   * Add given cluster to the list of clusters that should be automatically indexed.
   *
   * @param iClusterName Cluster to add.
   *
   * @return Current index instance.
   */
  OIndex<T> addCluster(final String iClusterName);

  /**
   * Remove given cluster from the list of clusters that should be automatically indexed.
   *
   * @param iClusterName Cluster to remove.
   *
   * @return Current index instance.
   */
  OIndex<T> removeCluster(final String iClusterName);

  /**
   * Indicates whether given index can be used to calculate result of
   * {@link com.orientechnologies.orient.core.sql.operator.OQueryOperatorEquality} operators.
   *
   * @return {@code true} if given index can be used to calculate result of
   * {@link com.orientechnologies.orient.core.sql.operator.OQueryOperatorEquality} operators.
   */
  boolean canBeUsedInEqualityOperators();

  boolean hasRangeQuerySupport();

  OIndexMetadata loadMetadata(ODocument iConfig);

  void setRebuildingFlag();

  void close();

  void preCommit(OIndexAbstract.IndexTxSnapshot snapshots);

  void addTxOperation(OIndexAbstract.IndexTxSnapshot snapshots, final OTransactionIndexChanges changes);

  void commit(OIndexAbstract.IndexTxSnapshot snapshots);

  void postCommit(OIndexAbstract.IndexTxSnapshot snapshots);

  void setType(OType type);

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this index.
   */
  void acquireAtomicExclusiveLock();
}
