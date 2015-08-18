/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.scm

import java.nio.file.Files
import java.nio.file.Path

import org.eclipse.jgit.api.Git
import spock.lang.Shared
import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class UpdateModuleTest extends Specification {

    @Shared
    Path baseFolder

    def setupSpec() {
        baseFolder = Files.createTempDirectory('test').toAbsolutePath()

        // create module a
        baseFolder.resolve('prj_aaa').mkdir()
        def dir = baseFolder.resolve('prj_aaa').toFile()
        def init = Git.init()
        def repo = init.setDirectory( dir ).call()
        new File(dir, 'file1.txt').text = 'Hello'
        new File(dir, 'file2.log').text = 'World'
        repo.add().addFilepattern('.').call()
        repo.commit().setAll(true).setMessage('First commit').call()
        repo.close()

        // create module b
        baseFolder.resolve('prj_bbb').mkdir()
        dir = baseFolder.resolve('prj_bbb').toFile()
        init = Git.init()
        repo = init.setDirectory( dir ).call()
        new File(dir, 'file1.txt').text = 'Ciao'
        new File(dir, 'file2.log').text = 'Mondo'
        repo.add().addFilepattern('.').call()
        repo.commit().setAll(true).setMessage('First commit').call()
        repo.close()

        // create module c
        baseFolder.resolve('prj_ccc').mkdir()
        dir = baseFolder.resolve('prj_ccc').toFile()
        init = Git.init()
        repo = init.setDirectory( dir ).call()
        new File(dir, 'file-x.txt').text = 'x'
        new File(dir, 'file-y.txt').text = 'y'
        repo.add().addFilepattern('.').call()
        repo.commit().setAll(true).setMessage('First commit').call()
        repo.close()

    }

    def cleanupSpec() {
        baseFolder?.deleteDir()
    }

    def 'should clone and update submodules' () {

        setup:
        // create the main project
        baseFolder.resolve('test/pipe').mkdirs()
        def dir = baseFolder.resolve('test/pipe').toFile()
        def init = Git.init()
        def repo = init.setDirectory( dir ).call()
        repo.submoduleAdd().setPath('prj_aaa').setURI( baseFolder.resolve('prj_aaa/.git').toString() ).call()
        repo.submoduleAdd().setPath('prj_bbb').setURI( baseFolder.resolve('prj_bbb/.git').toString() ).call()
        new File(dir, 'main.nf').text = 'main script'
        repo.add().addFilepattern('.').call()
        repo.commit().setAll(true).setMessage('First commit').call()

        when:
        def target = baseFolder.resolve('target')
        def manager = new AssetManager()
        manager.with {
            setRoot( target.toFile() )
            setHub( "file:${baseFolder}" )
            setPipeline('test/pipe')
        }

        manager.download()
        manager.updateModules()

        then:
        target.resolve('test/pipe').exists()
        target.resolve('test/pipe/.git').exists()
        target.resolve('test/pipe/main.nf').exists()

        target.resolve('test/pipe/prj_aaa').exists()
        target.resolve('test/pipe/prj_aaa/file1.txt').text == 'Hello'
        target.resolve('test/pipe/prj_aaa/file2.log').text == 'World'

        target.resolve('test/pipe/prj_bbb').exists()
        target.resolve('test/pipe/prj_bbb/file1.txt').text == 'Ciao'
        target.resolve('test/pipe/prj_bbb/file2.log').text == 'Mondo'
    }


    def 'should not clone submodules' () {

        setup:
        // create the main project
        baseFolder.resolve('test/pipe_2').mkdirs()
        def dir = baseFolder.resolve('test/pipe_2').toFile()
        def init = Git.init()
        def repo = init.setDirectory( dir ).call()
        repo.submoduleAdd().setPath('prj_aaa').setURI( baseFolder.resolve('prj_aaa/.git').toString() ).call()
        repo.submoduleAdd().setPath('prj_bbb').setURI( baseFolder.resolve('prj_bbb/.git').toString() ).call()
        new File(dir, 'main.nf').text = 'main script'
        new File(dir, 'nextflow.config').text = 'manifest.gitmodules = false'  // note: <-- this setting switch-off the submodule update
        repo.add().addFilepattern('.').call()
        repo.commit().setAll(true).setMessage('First commit').call()

        when:
        def target = baseFolder.resolve('target2')
        def manager = new AssetManager()
        manager.with {
            setRoot( target.toFile() )
            setHub( "file:${baseFolder}" )
            setPipeline('test/pipe_2')
        }

        manager.download()
        manager.updateModules()

        then:
        target.resolve('test/pipe_2').exists()
        target.resolve('test/pipe_2/.git').exists()
        target.resolve('test/pipe_2/main.nf').exists()

        !target.resolve('test/pipe_2/prj_aaa').exists()
        !target.resolve('test/pipe_2/prj_bbb').exists()
    }

    def 'should selected submodules' () {

        setup:
        // create the main project
        baseFolder.resolve('test/pipe_3').mkdirs()
        def dir = baseFolder.resolve('test/pipe_3').toFile()
        def init = Git.init()
        def repo = init.setDirectory( dir ).call()
        repo.submoduleAdd().setPath('prj_aaa').setURI( baseFolder.resolve('prj_aaa/.git').toString() ).call()
        repo.submoduleAdd().setPath('prj_bbb').setURI( baseFolder.resolve('prj_bbb/.git').toString() ).call()
        repo.submoduleAdd().setPath('prj_ccc').setURI( baseFolder.resolve('prj_ccc/.git').toString() ).call()
        new File(dir, 'main.nf').text = 'main script'
        new File(dir, 'nextflow.config').text = "manifest.gitmodules = 'prj_bbb,prj_ccc'"  // note: <-- this setting switch-off the submodule update
        repo.add().addFilepattern('.').call()
        repo.commit().setAll(true).setMessage('First commit').call()

        when:
        def target = baseFolder.resolve('target3')
        def manager = new AssetManager()
        manager.with {
            setRoot( target.toFile() )
            setHub( "file:${baseFolder}" )
            setPipeline('test/pipe_3')
        }

        manager.download()
        manager.updateModules()

        then:
        target.resolve('test/pipe_3').exists()
        target.resolve('test/pipe_3/.git').exists()
        target.resolve('test/pipe_3/main.nf').exists()

        !target.resolve('test/pipe_3/prj_aaa').exists()

        target.resolve('test/pipe_3/prj_bbb').exists()
        target.resolve('test/pipe_3/prj_bbb/file1.txt').text == 'Ciao'

        target.resolve('test/pipe_3/prj_ccc').exists()
        target.resolve('test/pipe_3/prj_ccc/file-x.txt').text == 'x'

    }


}
