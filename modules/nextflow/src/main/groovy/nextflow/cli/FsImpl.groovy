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

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.extension.FilesEx
import nextflow.file.FileHelper
import nextflow.file.FilePatternSplitter
import nextflow.plugin.Plugins

/**
 * CLI `fs` sub-command
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class FsImpl {

    enum Command {
        COPY,
        MOVE,
        LIST,
        CAT,
        REMOVE
    }

    void run(Command command, List<String> args) {
        Plugins.setup()

        try {
            switch( command ) {
                case COPY:
                    traverse(args[0]) { Path path -> copy(path, args[1] as Path) }
                    break
                case MOVE:
                    traverse(args[0]) { Path path -> move(path, args[1] as Path) }
                    break
                case LIST:
                    traverse(args[0]) { Path path -> list(path) }
                    break
                case CAT:
                    traverse(args[0]) { Path path -> cat(path) }
                    break
                case REMOVE:
                    traverse(args[0]) { Path path -> remove(path) }
                    break
            }
        }
        finally {
            Plugins.stop()
        }
    }

    private void traverse( String source, Closure op ) {
        // if it isn't a glob pattern simply return it a normalized absolute Path object
        def splitter = FilePatternSplitter.glob().parse(source)
        if( splitter.isPattern() ) {
            final scheme = splitter.scheme
            final folder = splitter.parent
            final pattern = splitter.fileName
            final fs = FileHelper.fileSystemForScheme(scheme)

            def opts = [:]
            opts.type = 'file'

            FileHelper.visitFiles(opts, fs.getPath(folder), pattern, op)
        }
        else {
            def normalised = splitter.strip(source)
            op.call(FileHelper.asPath(normalised))
        }
    }

    void copy(Path source, Path target) {
        FilesEx.copyTo(source, target)
    }

    void move(Path source, Path target) {
        FilesEx.moveTo(source, target)
    }

    void list(Path source) {
        println source.name
    }

    void cat(Path source) {
        String line
        def reader = Files.newBufferedReader(source, Charset.defaultCharset())
        while( line = reader.readLine() )
            println line
    }

    void remove(Path source) {
        Files.isDirectory(source) ? FilesEx.deleteDir(source) : FilesEx.delete(source)
    }

}