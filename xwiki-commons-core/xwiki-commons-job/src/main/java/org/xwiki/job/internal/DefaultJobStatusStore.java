/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.job.internal;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.job.JobManagerConfiguration;
import org.xwiki.job.JobStatusStore;
import org.xwiki.job.Request;
import org.xwiki.job.event.status.JobStatus;

/**
 * Default implementation of {@link JobStatusStorage}.
 * 
 * @version $Id$
 * @since 6.1M2
 */
@Component
@Singleton
public class DefaultJobStatusStore implements JobStatusStore, Initializable
{
    /**
     * The name of the file where the job status is stored.
     */
    private static final String FILENAME_STATUS = "status.xml";

    /**
     * Encoding used for file content and names.
     */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * The encoded version of a <code>null</code> value in the id list.
     */
    private static final String FOLDER_NULL = "&null";

    private static final JobStatus NOSTATUS = new DefaultJobStatus<Request>(null, null, null, false);

    /**
     * Used to get the storage directory.
     */
    @Inject
    private JobManagerConfiguration configuration;

    /**
     * The logger to log.
     */
    @Inject
    private Logger logger;

    // TODO: probably use JCache instead
    private Map<List<String>, JobStatus> cache;

    private JobStatusSerializer serializer;

    private ExecutorService executorService;

    class JobStatusSerializerRunnable implements Runnable
    {
        /**
         * The status to store.
         */
        private final JobStatus status;

        public JobStatusSerializerRunnable(JobStatus status)
        {
            this.status = status;
        }

        @Override
        public void run()
        {
            saveJobStatus(this.status);
        }
    }

    @Override
    public void initialize() throws InitializationException
    {
        try {
            this.serializer = new JobStatusSerializer();

            repair();
        } catch (Exception e) {
            this.logger.error("Failed to load jobs", e);
        }

        BasicThreadFactory threadFactory =
            new BasicThreadFactory.Builder().namingPattern("Job status serializer").daemon(true)
                .priority(Thread.MIN_PRIORITY).build();
        this.executorService =
            new ThreadPoolExecutor(0, 10, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), threadFactory);

        this.cache = Collections.synchronizedMap(new LRUMap(50));
    }

    /**
     * @param name the file or directory name to encode
     * @return the encoding name
     */
    private String encode(String name)
    {
        String encoded;

        if (name != null) {
            try {
                encoded = URLEncoder.encode(name, DEFAULT_ENCODING);
            } catch (UnsupportedEncodingException e) {
                // Should never happen

                encoded = name;
            }
        } else {
            encoded = FOLDER_NULL;
        }

        return encoded;
    }

    /**
     * Load jobs from directory.
     */
    private void repair()
    {
        File folder = this.configuration.getStorage();

        if (folder.exists()) {
            repairFolder(folder);
        }
    }

    /**
     * @param folder the folder from where to load the jobs
     */
    private void repairFolder(File folder)
    {
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                repairFolder(file);
            } else if (file.getName().equals(FILENAME_STATUS)) {
                try {
                    JobStatus status = loadStatus(folder);

                    if (status != null) {
                        File properFolder = getJobFolder(status.getRequest().getId());

                        if (!folder.equals(properFolder)) {
                            // Move the status in its right place
                            try {
                                FileUtils.moveFileToDirectory(file, properFolder, true);
                            } catch (IOException e) {
                                this.logger.error("Failed to move job status file", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    this.logger.warn("Failed to load job status in folder [{}]", folder, e);
                }
            }
        }
    }

    private JobStatus loadStatus(List<String> id)
    {
        return loadStatus(getJobFolder(id));
    }

    /**
     * @param folder the folder from where to load the job status
     */
    private JobStatus loadStatus(File folder)
    {
        File statusFile = new File(folder, FILENAME_STATUS);
        if (statusFile.exists()) {
            return loadJobStatus(statusFile);
        }

        return null;
    }

    /**
     * @param statusFile the file containing job status to load
     * @return the job status
     * @throws Exception when failing to load the job status from the file
     */
    private JobStatus loadJobStatus(File statusFile)
    {
        return (JobStatus) this.serializer.read(statusFile);
    }

    // JobStatusStorage

    /**
     * @param id the id of the job
     * @return the folder where to store the job related informations
     */
    private File getJobFolder(List<String> id)
    {
        File folder = this.configuration.getStorage();

        if (id != null) {
            for (String idElement : id) {
                folder = new File(folder, encode(idElement));
            }
        }

        return folder;
    }

    /**
     * @param status the job status to save
     * @throws IOException when falling to store the provided status
     */
    private void saveJobStatus(JobStatus status)
    {
        try {
            File statusFile = getJobFolder(status.getRequest().getId());
            statusFile = new File(statusFile, FILENAME_STATUS);

            this.serializer.write(status, statusFile);
        } catch (Exception e) {
            this.logger.warn("Failed to save job status [{}]", status, e);
        }
    }

    @Override
    public JobStatus getJobStatus(List<String> id)
    {
        JobStatus status = this.cache.get(id);

        if (status == null) {
            synchronized (this) {
                status = this.cache.get(id);

                if (status == null) {
                    try {
                        status = loadStatus(id);

                        this.cache.put(id, status);
                    } catch (Exception e) {
                        this.logger.warn("Failed to load job status for id [{}]", id, e);

                        this.cache.put(id, NOSTATUS);
                    }
                }
            }
        }

        return status == NOSTATUS ? null : status;
    }

    @Override
    public void store(JobStatus status)
    {
        store(status, false);
    }

    @Override
    public void storeAsync(JobStatus status)
    {
        store(status, true);
    }

    private void store(JobStatus status, boolean async)
    {
        if (status != null && status.getRequest() != null && status.getRequest().getId() != null) {
            this.cache.put(status.getRequest().getId(), status);

            // Only store Serializable job status on file system
            if (status instanceof Serializable) {
                if (async) {
                    this.executorService.execute(new JobStatusSerializerRunnable(status));
                } else {
                    saveJobStatus(status);
                }
            }
        }
    }

    @Override
    public void remove(List<String> id)
    {
        File jobFolder = getJobFolder(id);

        if (jobFolder.exists()) {
            try {
                FileUtils.deleteDirectory(jobFolder);
            } catch (IOException e) {
                this.logger.warn("Failed to delete job folder [{}]", jobFolder, e);
            }

            this.cache.remove(id);
        }

        this.cache.remove(id);
    }
}
