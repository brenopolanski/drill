/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.physical.impl.join;

import java.util.ArrayList;
import java.util.List;

import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.physical.impl.common.HashPartition;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.record.RecordBatch.IterOutcome;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.drill.exec.vector.IntVector;
import org.apache.drill.exec.vector.ValueVector;

public abstract class HashJoinProbeTemplate implements HashJoinProbe {

  VectorContainer container; // the outgoing container

  // Probe side record batch
  private RecordBatch probeBatch;

  private BatchSchema probeSchema;

  // Join type, INNER, LEFT, RIGHT or OUTER
  private JoinRelType joinType;

  private HashJoinBatch outgoingJoinBatch = null;

  private static final int TARGET_RECORDS_PER_BATCH = 4000;

  // Number of records to process on the probe side
  private int recordsToProcess = 0;

  // Number of records processed on the probe side
  private int recordsProcessed = 0;

  // Number of records in the output container
  private int outputRecords;

  // Indicate if we should drain the next record from the probe side
  private boolean getNextRecord = true;

  // Contains both batch idx and record idx of the matching record in the build side
  private int currentCompositeIdx = -1;

  // Current state the hash join algorithm is in
  private ProbeState probeState = ProbeState.PROBE_PROJECT;

  // For outer or right joins, this is a list of unmatched records that needs to be projected
  private List<Integer> unmatchedBuildIndexes = null;

  private  HashPartition partitions[];

  // While probing duplicates, retain current build-side partition in case need to continue
  // probing later on the same chain of duplicates
  private HashPartition currPartition;

  private int currRightPartition = 0; // for returning RIGHT/FULL
  IntVector read_left_HV_vector; // HV vector that was read from the spilled batch
  private int cycleNum = 0; // 1-primary, 2-secondary, 3-tertiary, etc.
  private HashJoinBatch.HJSpilledPartition spilledInners[]; // for the outer to find the partition
  private boolean buildSideIsEmpty = true;
  private int numPartitions = 1; // must be 2 to the power of bitsInMask
  private int partitionMask = 0; // numPartitions - 1
  private int bitsInMask = 0; // number of bits in the MASK
  private int rightHVColPosition;

  /**
   *  Setup the Hash Join Probe object
   *
   * @param probeBatch
   * @param outgoing
   * @param joinRelType
   * @param leftStartState
   * @param partitions
   * @param cycleNum
   * @param container
   * @param spilledInners
   * @param buildSideIsEmpty
   * @param numPartitions
   * @param rightHVColPosition
   */
  @Override
  public void setupHashJoinProbe(RecordBatch probeBatch, HashJoinBatch outgoing, JoinRelType joinRelType, IterOutcome leftStartState, HashPartition[] partitions, int cycleNum, VectorContainer container, HashJoinBatch.HJSpilledPartition[] spilledInners, boolean buildSideIsEmpty, int numPartitions, int rightHVColPosition) {
    this.container = container;
    this.spilledInners = spilledInners;
    this.probeBatch = probeBatch;
    this.probeSchema = probeBatch.getSchema();
    this.joinType = joinRelType;
    this.outgoingJoinBatch = outgoing;
    this.partitions = partitions;
    this.cycleNum = cycleNum;
    this.buildSideIsEmpty = buildSideIsEmpty;
    this.numPartitions = numPartitions;
    this.rightHVColPosition = rightHVColPosition;

    partitionMask = numPartitions - 1; // e.g. 32 --> 0x1F
    bitsInMask = Integer.bitCount(partitionMask); // e.g. 0x1F -> 5

    probeState = ProbeState.PROBE_PROJECT;
    this.recordsToProcess = 0;
    this.recordsProcessed = 0;

    // A special case - if the left was an empty file
    if ( leftStartState == IterOutcome.NONE ){
      changeToFinalProbeState();
    } else {
      this.recordsToProcess = probeBatch.getRecordCount();
    }

    // for those outer partitions that need spilling (cause their matching inners spilled)
    // initialize those partitions' current batches and hash-value vectors
    for ( HashPartition partn : this.partitions) {
      partn.allocateNewCurrentBatchAndHV();
    }

    currRightPartition = 0; // In case it's a Right/Full outer join

    // Initialize the HV vector for the first (already read) left batch
    if ( this.cycleNum > 0 ) {
      if ( read_left_HV_vector != null ) { read_left_HV_vector.clear();}
      if ( leftStartState != IterOutcome.NONE ) { // Skip when outer spill was empty
        read_left_HV_vector = (IntVector) probeBatch.getContainer().getLast();
      }
    }
  }

  /**
   * Append the given build side row into the outgoing container
   * @param buildSrcContainer The container for the right/inner side
   * @param buildSrcIndex build side index
   * @return The index for the last column (where the probe side would continue copying)
   */
  private int appendBuild(VectorContainer buildSrcContainer, int buildSrcIndex) {
    // "- 1" to skip the last "hash values" added column
    int lastColIndex = buildSrcContainer.getNumberOfColumns() - 1 ;
    for (int vectorIndex = 0; vectorIndex < lastColIndex; vectorIndex++) {
      ValueVector destVector = container.getValueVector(vectorIndex).getValueVector();
      ValueVector srcVector = buildSrcContainer.getValueVector(vectorIndex).getValueVector();
      destVector.copyEntry(container.getRecordCount(), srcVector, buildSrcIndex);
    }
    return lastColIndex;
  }
  /**
   * Append the given probe side row into the outgoing container, following the build side part
   * @param probeSrcContainer The container for the left/outer side
   * @param probeSrcIndex probe side index
   * @param baseIndex The column index to start copying into (following the build columns)
   */
  private void appendProbe(VectorContainer probeSrcContainer, int probeSrcIndex, int baseIndex) {
    for (int vectorIndex = baseIndex; vectorIndex < container.getNumberOfColumns(); vectorIndex++) {
      ValueVector destVector = container.getValueVector(vectorIndex).getValueVector();
      ValueVector srcVector = probeSrcContainer.getValueVector(vectorIndex - baseIndex).getValueVector();
      destVector.copyEntry(container.getRecordCount(), srcVector, probeSrcIndex);
    }
  }
  /**
   *  A special version of the VectorContainer's appendRow for the HashJoin; (following a probe) it
   *  copies the build and probe sides into the outgoing container. (It uses a composite
   *  index for the build side)
   * @param buildSrcContainers The containers list for the right/inner side
   * @param compositeBuildSrcIndex Composite build index
   * @param probeSrcContainer The single container for the left/outer side
   * @param probeSrcIndex Index in the outer container
   * @return Number of rows in this container (after the append)
   */
  private int outputRow(ArrayList<VectorContainer> buildSrcContainers, int compositeBuildSrcIndex,
                        VectorContainer probeSrcContainer, int probeSrcIndex) {
    int buildBatchIndex = compositeBuildSrcIndex >>> 16;
    int buildOffset = compositeBuildSrcIndex & 65535;
    int baseInd = 0;
    if ( buildSrcContainers != null ) { baseInd = appendBuild(buildSrcContainers.get(buildBatchIndex), buildOffset); }
    if ( probeSrcContainer != null ) { appendProbe(probeSrcContainer, probeSrcIndex, baseInd); }
    return container.incRecordCount();
  }

  /**
   * A customised version of the VectorContainer's appendRow for HashJoin - used for Left
   * Outer Join when there is no build side match - hence need a base index in
   * this container's wrappers from where to start appending
   * @param probeSrcContainer
   * @param probeSrcIndex
   * @param baseInd - index of this container's wrapper to start at
   * @return
   */
  private int outputOuterRow(VectorContainer probeSrcContainer, int probeSrcIndex, int baseInd) {
    appendProbe(probeSrcContainer, probeSrcIndex, baseInd);
    return container.incRecordCount();
  }


  private void executeProjectRightPhase(int currBuildPart) {
    while (outputRecords < TARGET_RECORDS_PER_BATCH && recordsProcessed < recordsToProcess) {
      outputRecords =
        outputRow(partitions[currBuildPart].getContainers(), unmatchedBuildIndexes.get(recordsProcessed),
          null /* no probeBatch */, 0 /* no probe index */ );
      recordsProcessed++;
    }
  }

  private void executeProbePhase() throws SchemaChangeException {

    while (outputRecords < TARGET_RECORDS_PER_BATCH && probeState != ProbeState.DONE && probeState != ProbeState.PROJECT_RIGHT) {

      // Check if we have processed all records in this batch we need to invoke next
      if (recordsProcessed == recordsToProcess) {

        // Done processing all records in the previous batch, clean up!
        for (VectorWrapper<?> wrapper : probeBatch) {
          wrapper.getValueVector().clear();
        }

        IterOutcome leftUpstream = outgoingJoinBatch.next(HashJoinHelper.LEFT_INPUT, probeBatch);

        switch (leftUpstream) {
          case NONE:
          case NOT_YET:
          case STOP:
            recordsProcessed = 0;
            recordsToProcess = 0;
            changeToFinalProbeState();
            // in case some outer partitions were spilled, need to spill their last batches
            for ( HashPartition partn : partitions ) {
              if ( ! partn.isSpilled() ) { continue; } // skip non-spilled
              partn.completeAnOuterBatch(false);
              // update the partition's spill record with the outer side
              HashJoinBatch.HJSpilledPartition sp = spilledInners[partn.getPartitionNum()];
              sp.outerSpillFile = partn.getSpillFile();
              sp.outerSpilledBatches = partn.getPartitionBatchesCount();

              partn.closeWriter();
            }

            continue;

          case OK_NEW_SCHEMA:
            if (probeBatch.getSchema().equals(probeSchema)) {
              for ( HashPartition partn : partitions ) { partn.updateBatches(); }

            } else {
              throw SchemaChangeException.schemaChanged("Hash join does not support schema changes in probe side.",
                probeSchema,
                probeBatch.getSchema());
            }
          case OK:
            recordsToProcess = probeBatch.getRecordCount();
            recordsProcessed = 0;
            // If we received an empty batch do nothing
            if (recordsToProcess == 0) {
              continue;
            }
            if ( cycleNum > 0 ) {
              read_left_HV_vector = (IntVector) probeBatch.getContainer().getLast(); // Needed ?
            }
        }
      }

        int probeIndex = -1;
      // Check if we need to drain the next row in the probe side
      if (getNextRecord) {

        if ( !buildSideIsEmpty ) {
          int hashCode = ( cycleNum == 0 ) ?
            partitions[0].getProbeHashCode(recordsProcessed)
            : read_left_HV_vector.getAccessor().get(recordsProcessed);
          int currBuildPart = hashCode & partitionMask ;
          hashCode >>>= bitsInMask;

          // Set and keep the current partition (may be used again on subsequent probe calls as
          // inner rows of duplicate key are processed)
          currPartition = partitions[currBuildPart]; // inner if not spilled, else outer

          // If the matching inner partition was spilled
          if ( outgoingJoinBatch.isSpilledInner(currBuildPart) ) {
            // add this row to its outer partition (may cause a spill, when the batch is full)

            currPartition.appendOuterRow(hashCode, recordsProcessed);

            recordsProcessed++; // done with this outer record
            continue; // on to the next outer record
          }

          probeIndex = currPartition.probeForKey(recordsProcessed, hashCode);

        }

        if (probeIndex != -1) {

          /* The current probe record has a key that matches. Get the index
           * of the first row in the build side that matches the current key
           * (and record this match in the bitmap, in case of a FULL/RIGHT join)
           */
          currentCompositeIdx = currPartition.getStartIndex(probeIndex);

          outputRecords =
            outputRow(currPartition.getContainers(), currentCompositeIdx,
              probeBatch.getContainer(), recordsProcessed);

          /* Projected single row from the build side with matching key but there
           * may be more rows with the same key. Check if that's the case
           */
          currentCompositeIdx = currPartition.getNextIndex(currentCompositeIdx);
          if (currentCompositeIdx == -1) {
            /* We only had one row in the build side that matched the current key
             * from the probe side. Drain the next row in the probe side.
             */
            recordsProcessed++;
          } else {
            /* There is more than one row with the same key on the build side
             * don't drain more records from the probe side till we have projected
             * all the rows with this key
             */
            getNextRecord = false;
          }
        } else { // No matching key

          // If we have a left outer join, project the outer side
          if (joinType == JoinRelType.LEFT || joinType == JoinRelType.FULL) {

            outputRecords =
              outputOuterRow(probeBatch.getContainer(), recordsProcessed, rightHVColPosition);
          }
          recordsProcessed++;
        }
      }
      else { // match the next inner row with the same key

        currPartition.setRecordMatched(currentCompositeIdx);

        outputRecords =
          outputRow(currPartition.getContainers(), currentCompositeIdx,
            probeBatch.getContainer(), recordsProcessed);

        currentCompositeIdx = currPartition.getNextIndex(currentCompositeIdx);

        if (currentCompositeIdx == -1) {
          // We don't have any more rows matching the current key on the build side, move on to the next probe row
          getNextRecord = true;
          recordsProcessed++;
        }
      }
    }

  }

  /**
   *  Perform the probe, till the outgoing is full, or no more rows to probe.
   *  Performs the inner or left-outer join while there are left rows,
   *  when done, continue with right-outer, if appropriate.
   * @return Num of output records
   * @throws SchemaChangeException
   */
  @Override
  public int probeAndProject() throws SchemaChangeException {

    outputRecords = 0;

    // When handling spilled partitions, the state becomes DONE at the end of each partition
    if ( probeState == ProbeState.DONE ) {
      return outputRecords; // that is zero
    }

    if (probeState == ProbeState.PROBE_PROJECT) {
      executeProbePhase();
    }

    if (probeState == ProbeState.PROJECT_RIGHT) {
      // Inner probe is done; now we are here because we still have a RIGHT OUTER (or a FULL) join

      do {

        if (unmatchedBuildIndexes == null) { // first time for this partition ?
          if ( buildSideIsEmpty ) { return outputRecords; } // in case of an empty right
          // Get this partition's list of build indexes that didn't match any record on the probe side
          unmatchedBuildIndexes = partitions[currRightPartition].getNextUnmatchedIndex();
          recordsProcessed = 0;
          recordsToProcess = unmatchedBuildIndexes.size();
        }

        // Project the list of unmatched records on the build side
        executeProjectRightPhase(currRightPartition);

        if ( recordsProcessed < recordsToProcess ) { // more records in this partition?
          return outputRecords;  // outgoing is full; report and come back later
        } else {
          currRightPartition++; // on to the next right partition
          unmatchedBuildIndexes = null;
        }

      }   while ( currRightPartition < numPartitions );

      probeState = ProbeState.DONE; // last right partition was handled; we are done now
    }

    return outputRecords;
  }

  @Override
  public void changeToFinalProbeState() {
    // We are done with the (left) probe phase.
    // If it's a RIGHT or a FULL join then need to get the unmatched indexes from the build side
    probeState =
      (joinType == JoinRelType.RIGHT || joinType == JoinRelType.FULL) ? ProbeState.PROJECT_RIGHT :
        ProbeState.DONE; // else we're done
  }
}
