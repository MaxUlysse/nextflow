/*
 * Copyright 2023, Seqera Labs
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

package nextflow.script.params

import static test.TestParser.parseAndReturnProcess

import test.Dsl2Spec
/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class MapInParamTest extends Dsl2Spec {

    def 'should create input maps'() {
        setup:
        def text = '''
            x = 'Hola mundo'

            process hola {
              input:
              map( p: val(p) )
              map( p: val(p), q: val(q) )
              map( v: val(v), fa: path('file_name.fa') )
              map( v: val(v), txt: path('file_name.txt'), stdin: '-' )
              map( p: val(p), z: path(z, stageAs: 'file*') )

              /foo/
            }

            workflow {
              hola(x, x, 'str', 'ciao', x)
            }
            '''

        when:
        def process = parseAndReturnProcess(text)
        MapInParam in1 = process.config.getInputs().get(0)
        MapInParam in2 = process.config.getInputs().get(1)
        MapInParam in3 = process.config.getInputs().get(2)
        MapInParam in4 = process.config.getInputs().get(3)
        MapInParam in5 = process.config.getInputs().get(4)

        then:
        process.config.getInputs().size() == 5

        in1.inner.size() == 1
        in1.inner.get(0) instanceof ValueInParam
        in1.inner.get(0).index == 0
        in1.inner.get(0).innerIndex == 0
        in1.inner.get(0).name == 'p'
        in1.inChannel.val == 'Hola mundo'
        and:
        in2.inner.size() == 2
        in2.inner.get(0) instanceof ValueInParam
        in2.inner.get(0).name == 'p'
        in2.inner.get(0).index == 1
        in2.inner.get(0).innerIndex == 0
        in2.inner.get(1) instanceof ValueInParam
        in2.inner.get(1).name == 'q'
        in2.inner.get(1).index == 1
        in2.inner.get(1).innerIndex == 1
        in2.inChannel.val == 'Hola mundo'
        and:
        in3.inner.size() == 2
        in3.inner.get(0) instanceof ValueInParam
        in3.inner.get(0).name == 'v'
        in3.inner.get(0).index == 2
        in3.inner.get(0).innerIndex == 0
        in3.inner.get(1) instanceof FileInParam
        in3.inner.get(1).name == 'file_name.fa'
        in3.inner.get(1).filePattern == 'file_name.fa'
        in3.inner.get(1).index == 2
        in3.inner.get(1).innerIndex == 1
        in3.inChannel.val == 'str'
        and:
        in4.inner.size() == 3
        in4.inner.get(0) instanceof ValueInParam
        in4.inner.get(0).name == 'v'
        in4.inner.get(0).index == 3
        in4.inner.get(0).innerIndex == 0
        in4.inner.get(1) instanceof FileInParam
        in4.inner.get(1).name == 'file_name.txt'
        in4.inner.get(1).filePattern == 'file_name.txt'
        in4.inner.get(1).index == 3
        in4.inner.get(1).innerIndex == 1
        in4.inner.get(2) instanceof StdInParam
        in4.inner.get(2).name == '-'
        in4.inner.get(2).index == 3
        in4.inner.get(2).innerIndex == 2
        in4.inChannel.val == 'ciao'
        and:
        in5.inner.size() == 2
        in5.inner.get(0) instanceof ValueInParam
        in5.inner.get(0).index == 4
        in5.inner.get(0).innerIndex == 0
        in5.inner.get(1) instanceof FileInParam
        in5.inner.get(1).name == 'z'
        in5.inner.get(1).filePattern == 'file*'
        in5.inner.get(1).index == 4
        in5.inner.get(1).innerIndex == 1

    }

}
