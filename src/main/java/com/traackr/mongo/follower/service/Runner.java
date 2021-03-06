/**
 * MIT License
 *
 * Copyright (c) 2017 Traackr, Inc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 */
package com.traackr.mongo.follower.service;

import static com.traackr.mongo.follower.util.OplogTimestampHelper.getOplogTimestamp;

import com.traackr.mongo.follower.exceptions.FailedToStartException;
import com.traackr.mongo.follower.interfaces.MongoEventListener;
import com.traackr.mongo.follower.model.GlobalParams;
import com.traackr.mongo.follower.model.OpLogTailerParams;
import com.traackr.mongo.follower.model.OplogTimestampWriter;
import com.traackr.mongo.follower.model.Record;
import com.traackr.mongo.follower.model.FollowerConfig;
import com.traackr.mongo.follower.util.KillSwitch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author wwinder
 * Created on: 7/18/17
 */
public class Runner {
  public static void run(FollowerConfig config) throws FailedToStartException {
    // Initialize oplog dir.
    try {
      Path oplogFilePath = Paths.get(config.getOplogFile());
      Path parentDirectory = oplogFilePath.getParent();
      if (parentDirectory == null) {
        throw new FailedToStartException(
            String.format(
                "Could not find containing directory for oplog file %s",
                config.getOplogFile()));
      }
      if (!Files.exists(parentDirectory)) {
        Files.createDirectories(parentDirectory);
      }
    } catch (InvalidPathException ipe) {
      throw new FailedToStartException(
          String.format("Invalid path given for oplog file: %s", config.getOplogFile()),
          ipe);
    } catch (IOException ioe) {
      throw new FailedToStartException("Failed to create oplog file directory", ioe);
    }

    // Global properties.
    GlobalParams globalParams = new GlobalParams(
        new KillSwitch(),
        getOplogTimestamp(config.getOplogFile()),
        config.getOplogDelayMinutes(),
        config.getOplogUpdateIntervalMinutes(),
        null,
        false
    );

    // Mongo properties
    OpLogTailerParams mongoParams = null;
    mongoParams = OpLogTailerParams.with(
        globalParams,
        config.getInitialExport(),
        config.getQueue(),
        config.getMongoConnectionString(),
        config.getMongoDatabase(),
        config.getMongoCollection());

    // Initialize OpLogTail
    MongoFollower oplogTailer = new MongoFollower(mongoParams);

    // Initialize OpLogProcessor
    OpLogProcessor oplogProcessor = new OpLogProcessor(
        globalParams, config.getQueue(), config.getListener());

    // Oplog writer
    OplogTimestampWriter oplogWriter = new OplogTimestampWriter(globalParams);

    ///////////////////
    // Start threads //
    ///////////////////
    launchThreads(config.getQueue(), oplogTailer, oplogProcessor, oplogWriter);
  }

  private static void launchThreads(
      final BlockingQueue<Record> queue,
      MongoFollower oplogTailer,
      OpLogProcessor oplogProcessor,
      OplogTimestampWriter oplogWriter) {
    final ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

    pool.submit(oplogTailer);
    pool.submit(oplogProcessor);
    pool.submit(oplogWriter);

    // This seems weird.
    pool.submit(() -> {
      while (!Thread.interrupted() && pool.getActiveCount() != 0) {
        //logger.info("OplogQueue size: " + queue.size());

        if (pool.getActiveCount() == 0) {
          //logger.error("The threads are gone!!");
          System.exit(-1);
        } else if (pool.getActiveCount() != 2) {
          //logger.error("Only one processor thread is running!!");
        }
        try {
          Thread.sleep(10000);
        } catch (InterruptedException e) {
          //logger.error("Sleep threw an exception.", e);
          Thread.interrupted();
        }
      }
    });
  }

  /**
   * Load settings from property file:
   * dry-run
   * oplog-file
   * initial-export
   * mongo.connection-string
   * mongo.database
   * mongo.collection
   */
  public static void run(Properties properties, MongoEventListener listener)
      throws FailedToStartException {
    try {
      FollowerConfig config = FollowerConfig.builder()
          .listener(listener)
          .queue(new ArrayBlockingQueue<>(Integer.valueOf(properties.getProperty("queue-size"))))
          .oplogFile(properties.getProperty("oplog-file"))
          .oplogDelayMinutes(Integer.valueOf(properties.getProperty("mongo.oplog-delay")))
          .oplogUpdateIntervalMinutes(
              Integer.valueOf(properties.getProperty("mongo.oplog-interval")))
          .initialExport(Boolean.valueOf(properties.getProperty("initial-export")))
          .mongoConnectionString(properties.getProperty("mongo.connection-string"))
          .mongoDatabase(properties.getProperty("mongo.database"))
          .mongoCollection(properties.getProperty("mongo.collection"))
          .build();
      run(config);
    } catch (Exception e) {
      throw new FailedToStartException("Problem initializing configuration.", e);
    }
  }
}
