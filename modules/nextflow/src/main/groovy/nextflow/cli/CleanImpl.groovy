/*
 * Copyright 2020-2022, Seqera Labs
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
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

package nextflow.cli

import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

import com.google.common.hash.HashCode
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.cache.CacheDB
import nextflow.Global
import nextflow.ISession
import nextflow.Session
import nextflow.config.ConfigBuilder
import nextflow.exception.AbortOperationException
import nextflow.file.FileHelper
import nextflow.plugin.Plugins
import nextflow.trace.TraceRecord
import nextflow.util.HistoryFile.Record

/**
 * CLI `clean` sub-command
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @author Lorenz Gerber <lorenzottogerber@gmail.com>
 */
@Slf4j
@CompileStatic
class CleanImpl implements CacheBase {

    interface Options {
        String getAfter()
        String getBefore()
        String getBut()
        boolean getDryRun()
        boolean getForce()
        boolean getKeepLogs()
        boolean getQuiet()
        List<String> getArgs()

        ILauncherOptions getLauncherOptions()
    }

    @Delegate
    private Options options

    private CacheDB currentCacheDb

    private Map<HashCode, Short> dryHash = new HashMap<>()

    CleanImpl(Options options) {
        this.options = options
    }

    void run() {
        init()
        validateOptions()
        createSession()
        Plugins.setup()
        listIds().each { entry -> cleanup(entry) }
    }

    /**
     * Create the NF session which can be required to access cloud file store
     */
    private void createSession() {
        Global.setLazySession {
            final builder = new ConfigBuilder()
                    .setShowClosures(true)
                    .showMissingVariables(true)
                    .setLauncherOptions(launcherOptions)
                    .setBaseDir(Paths.get('.'))

            final config = builder.buildConfigObject()
            return (ISession) new Session(config)
        }
    }

    /**
     * Extra CLI option validation
     */
    private void validateOptions() {

        if( !dryRun && !force )
            throw new AbortOperationException("Neither -f or -n specified -- refused to clean")
    }

    /**
     * Given a history entry clean up execution cache, deleting
     * task work directories and cache DB records
     *
     * @param entry
     *      A {@link Record} object representing a row in the history log file
     */
    private void cleanup(Record entry) {
        currentCacheDb = cacheFor(entry).openForRead()
        // -- remove each entry and work dir
        currentCacheDb.eachRecord(this.&removeRecord)
        // -- close the cache
        currentCacheDb.close()

        // -- STOP HERE !
        if( dryRun || keepLogs ) return

        // -- remove the index file
        currentCacheDb.deleteIndex()
        // -- remove the session from the history file
        history.deleteEntry(entry)
        // -- check if exists another history entry for the same session
        if( !history.checkExistsById(entry.sessionId)) {
            currentCacheDb.drop()
        }
    }

    /**
     * Check if a tasks can be removed during a dry-run simulation.
     *
     * @param hash
     *      The task unique hash code
     * @param refCount
     *      The number of times the task cache is references by other run instances
     * @return
     *      {@code true} when task will be removed by the clean command, {@code false} otherwise i.e.
     *      entry cannot be deleted because is referenced by other run instances
     */
    private boolean wouldRemove(HashCode hash, Integer refCount) {

        if( dryHash.containsKey(hash) ) {
            refCount = dryHash.get(hash)-1
        }

        if( refCount == 1 ) {
            dryHash.remove(hash)
            return true
        }
        else {
            dryHash.put(hash, (short)refCount)
            return false
        }

    }

    /**
     * Delete task cache entry
     *
     * @param hash The task unique hash code
     * @param record The task {@link TraceRecord}
     * @param refCount The number of times the task cache is references by other run instances
     */
    private void removeRecord(HashCode hash, TraceRecord record, int refCount) {
        if( dryRun ) {
            if( wouldRemove(hash,refCount) )
                printMessage(record.workDir,true)
            return
        }

        // decrement the ref count in the db
        def proceed = keepLogs || currentCacheDb.removeTaskEntry(hash)
        if( proceed ) {
            // delete folder
            if( deleteFolder(FileHelper.asPath(record.workDir), keepLogs)) {
                if(!quiet) printMessage(record.workDir,false)
            }

        }
    }

    private printMessage(String path, boolean dryRun) {
        if( dryRun ) {
            println keepLogs ? "Would remove temp files from ${path}" : "Would remove ${path}"
        }
        else {
            println keepLogs ? "Removed temp files from ${path}" : "Removed ${path}"
        }
    }

    /**
     * Traverse a directory structure and delete all the content
     *
     * @param folder
     *      The directory to delete
     * @return
     *      {@code true} in the directory was removed, {@code false}  otherwise
     */
    private boolean deleteFolder( Path folder, boolean keepLogs ) {

        def result = true
        Files.walkFileTree(folder, new FileVisitor<Path>() {

            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                result ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                final canDelete = !keepLogs || ( keepLogs &&  !(file.name.startsWith('.command.')  || file.name == '.exitcode'))
                if( canDelete && !delete0(file,false) ) {
                    result = false
                    if(!quiet) System.err.println "Failed to remove ${file.toUriString()}"
                }

                result ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE
            }

            @Override
            FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if( !keepLogs && result && !delete0(dir,true) ) {
                    result = false
                    if(!quiet) System.err.println "Failed to remove ${dir.toUriString()}"
                }
                
                result ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE
            }
        })

        return result
    }

    private static delete0(Path path, boolean dir) {
        try {
            log.trace "Deleting path [dir=$dir]: ${path.toUriString()}"
            Files.delete(path)
            return true
        }
        catch( IOException e ) {
            // kind of hack: directory deletion
            if( dir && path.scheme=='gs' && e instanceof NoSuchFileException )
                return true
            log.debug("Failed to remove path: ${path.toUriString()}", e)
            return false
        }
    }

}