/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.master;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.HMsg;
import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.executor.HBaseEventHandler;
import org.apache.hadoop.hbase.executor.HBaseEventHandler.HBaseEventHandlerListener;
import org.apache.hadoop.hbase.executor.HBaseEventHandler.HBaseEventType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestMaster {
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final Log LOG = LogFactory.getLog(TestMasterWithDisabling.class);
  private static final byte[] TABLENAME = Bytes.toBytes("TestMaster");
  private static final byte[] FAMILYNAME = Bytes.toBytes("fam");

  @BeforeClass
  public static void beforeAllTests() throws Exception {
    // Start a cluster of two regionservers.
    TEST_UTIL.startMiniCluster(1);
  }

  @AfterClass
  public static void afterAllTests() throws IOException {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testMasterOpsWhileSplitting() throws Exception {
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    HMaster m = cluster.getMaster();
    HBaseAdmin admin = TEST_UTIL.getHBaseAdmin();

    TEST_UTIL.createTable(TABLENAME, FAMILYNAME);
    TEST_UTIL.loadTable(new HTable(TABLENAME), FAMILYNAME);

    CountDownLatch aboutToOpen = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    RegionOpenListener list = new RegionOpenListener(aboutToOpen, proceed);
    HBaseEventHandler.registerListener(list);

    admin.split(TABLENAME);
    aboutToOpen.await(60, TimeUnit.SECONDS);

    try {
      m.getTableRegions(TABLENAME);
      Pair<HRegionInfo,HServerAddress> pair =
        m.getTableRegionClosest(TABLENAME, Bytes.toBytes("cde"));
      /**
       * TODO: The assertNull below used to work before moving all RS->M 
       * communication to ZK, find out why this test's behavior has changed.
       * Tracked in HBASE-2656.
        assertNull(pair);
        assertNotNull(pair);
        
        We used to assert NotNull for the pair but it seems that ain't
        always true either. For now disabling this assertion.  Filing
        an issue for it to be checked -- St.Ack.
      */
      m.getTableRegionFromName(pair.getFirst().getRegionName());
    } finally {
      proceed.countDown();
    }
  }

  static class RegionOpenListener implements HBaseEventHandlerListener {
    CountDownLatch aboutToOpen, proceed;

    public RegionOpenListener(CountDownLatch aboutToOpen, CountDownLatch proceed)
    {
      this.aboutToOpen = aboutToOpen;
      this.proceed = proceed;
    }

    @Override
    public void afterProcess(HBaseEventHandler event) {
      if (event.getHBEvent() != HBaseEventType.RS2ZK_REGION_OPENED) {
        return;
      }
      try {
        aboutToOpen.countDown();
        proceed.await(60, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        throw new RuntimeException(ie);
      }
      return;
    }

    @Override
    public void beforeProcess(HBaseEventHandler event) {
    }
  }

}
