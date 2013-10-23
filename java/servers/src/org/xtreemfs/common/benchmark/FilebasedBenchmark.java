/*
 * Copyright (c) 2012-2013 by Jens V. Fischer, Zuse Institute Berlin
 *               
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.common.benchmark;

import org.xtreemfs.common.libxtreemfs.AdminClient;

/**
 * Abstract baseclass for filebased benchmarks.
 * <p/>
 * A filebased benchmark writes or reads lots of small files.
 * 
 * @author jensvfischer
 */
abstract class FilebasedBenchmark extends RandomBenchmark {

    static final String BENCHMARK_FILENAME = "benchmarks/randomBenchmark/benchFile";
    final int           filesize;

    FilebasedBenchmark(Config config, AdminClient client, VolumeManager volumeManager) throws Exception {
        super(config, client, volumeManager);
        this.filesize = config.getFilesize();
    }

    static String getBenchmarkFilename() {
        return BENCHMARK_FILENAME;
    }
}
