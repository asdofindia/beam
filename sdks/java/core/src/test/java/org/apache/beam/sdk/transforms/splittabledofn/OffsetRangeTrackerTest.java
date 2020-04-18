/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.transforms.splittabledofn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.beam.sdk.io.range.OffsetRange;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link OffsetRangeTracker}. */
@RunWith(JUnit4.class)
public class OffsetRangeTrackerTest {
  @Rule public final ExpectedException expected = ExpectedException.none();

  @Test
  public void testTryClaim() throws Exception {
    OffsetRange range = new OffsetRange(100, 200);
    OffsetRangeTracker tracker = new OffsetRangeTracker(range);
    assertEquals(range, tracker.currentRestriction());
    assertTrue(tracker.tryClaim(100L));
    assertTrue(tracker.tryClaim(150L));
    assertTrue(tracker.tryClaim(199L));
    assertFalse(tracker.tryClaim(200L));
  }

  @Test
  public void testCheckpointUnstarted() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    SplitResult res = tracker.trySplit(0);
    assertEquals(new OffsetRange(100, 100), res.getPrimary());
    assertEquals(new OffsetRange(100, 200), res.getResidual());
    tracker.checkDone();
  }

  @Test
  public void testCheckpointJustStarted() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(100L));
    OffsetRange checkpoint = tracker.trySplit(0).getResidual();
    assertEquals(new OffsetRange(100, 101), tracker.currentRestriction());
    assertEquals(new OffsetRange(101, 200), checkpoint);
    tracker.checkDone();
  }

  @Test
  public void testCheckpointRegular() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(105L));
    assertTrue(tracker.tryClaim(110L));
    OffsetRange checkpoint = tracker.trySplit(0).getResidual();
    assertEquals(new OffsetRange(100, 111), tracker.currentRestriction());
    assertEquals(new OffsetRange(111, 200), checkpoint);
    tracker.checkDone();
  }

  @Test
  public void testCheckpointClaimedLast() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(105L));
    assertTrue(tracker.tryClaim(110L));
    assertTrue(tracker.tryClaim(199L));
    SplitResult checkpoint = tracker.trySplit(0);
    assertEquals(new OffsetRange(100, 200), tracker.currentRestriction());
    assertNull(checkpoint);
    tracker.checkDone();
  }

  @Test
  public void testCheckpointAfterFailedClaim() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(105L));
    assertTrue(tracker.tryClaim(110L));
    assertTrue(tracker.tryClaim(160L));
    assertFalse(tracker.tryClaim(240L));
    assertNull(tracker.trySplit(0));
    tracker.checkDone();
  }

  @Test
  public void testTrySplitAfterCheckpoint() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    tracker.tryClaim(105L);
    tracker.trySplit(0);
    assertNull(tracker.trySplit(0.1));
  }

  @Test
  public void testTrySplit() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    tracker.tryClaim(100L);
    SplitResult splitRes = tracker.trySplit(0.509);
    assertEquals(new OffsetRange(100, 150), splitRes.getPrimary());
    assertEquals(new OffsetRange(150, 200), splitRes.getResidual());

    splitRes = tracker.trySplit(1);
    assertNull(splitRes);
  }

  @Test
  public void testTrySplitAtEmptyRange() throws Exception {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 100));
    assertNull(tracker.trySplit(0));
    assertNull(tracker.trySplit(0.1));
    assertNull(tracker.trySplit(1));
  }

  @Test
  public void testNonMonotonicClaim() throws Exception {
    expected.expectMessage("Trying to claim offset 103 while last attempted was 110");
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(105L));
    assertTrue(tracker.tryClaim(110L));
    tracker.tryClaim(103L);
  }

  @Test
  public void testClaimBeforeStartOfRange() throws Exception {
    expected.expectMessage("Trying to claim offset 90 before start of the range [100, 200)");
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    tracker.tryClaim(90L);
  }

  @Test
  public void testCheckDoneAfterTryClaimPastEndOfRange() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(150L));
    assertTrue(tracker.tryClaim(175L));
    assertFalse(tracker.tryClaim(220L));
    tracker.checkDone();
  }

  @Test
  public void testCheckDoneAfterTryClaimAtEndOfRange() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(150L));
    assertTrue(tracker.tryClaim(175L));
    assertFalse(tracker.tryClaim(200L));
    tracker.checkDone();
  }

  @Test
  public void testCheckDoneAfterTryClaimRightBeforeEndOfRange() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(150L));
    assertTrue(tracker.tryClaim(175L));
    assertTrue(tracker.tryClaim(199L));
    tracker.checkDone();
  }

  @Test
  public void testCheckDoneWhenNotDone() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertTrue(tracker.tryClaim(150L));
    assertTrue(tracker.tryClaim(175L));
    expected.expectMessage(
        "Last attempted offset was 175 in range [100, 200), "
            + "claiming work in [176, 200) was not attempted");
    tracker.checkDone();
  }

  @Test
  public void testBacklogUnstarted() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(0, 200));
    assertEquals(200, tracker.getSize(), 0.001);

    tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    assertEquals(100, tracker.getSize(), 0.001);
  }

  @Test
  public void testBacklogFinished() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(0, 200));
    tracker.tryClaim(300L);
    assertEquals(0, tracker.getSize(), 0.001);

    tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    tracker.tryClaim(300L);
    assertEquals(0., tracker.getSize(), 0.001);
  }

  @Test
  public void testBacklogPartiallyCompleted() {
    OffsetRangeTracker tracker = new OffsetRangeTracker(new OffsetRange(0, 200));
    tracker.tryClaim(150L);
    assertEquals(50., tracker.getSize(), 0.001);

    tracker = new OffsetRangeTracker(new OffsetRange(100, 200));
    tracker.tryClaim(150L);
    assertEquals(50., tracker.getSize(), 0.001);
  }
}
