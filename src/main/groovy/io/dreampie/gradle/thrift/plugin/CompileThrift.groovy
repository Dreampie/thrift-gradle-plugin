/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dreampie.gradle.thrift.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs

class CompileThrift extends DefaultTask {

    @InputFiles
    Set<File> sourceFiles = []

    @InputFiles
    Set<File> sourceDirs = []

    @OutputDirectory
    File outputDir

    @Input
    Set<File> includeDirs = []

    @Input
    String thriftExecutable = 'thrift'

    @Input
    final Map<String, String> generators = new LinkedHashMap<>()

    @Input
    boolean createGenFolder = false

    @Input
    boolean recurse

    @Input
    boolean allowNegKeys

    @Input
    boolean allow64bitsConsts

    boolean nowarn
    boolean strict
    boolean verbose
    boolean debug

    def thriftExecutable(Object thriftExecutable) {
        this.thriftExecutable = String.valueOf(thriftExecutable)
    }

    def sourceFiles(Object... sourceFiles) {
        this.sourceFiles(Arrays.asList(sourceFiles))
    }

    def sourceFiles(Collection<Object> sourceFiles) {
        sourceFiles.each { sourceFile ->
            this.sourceFiles.add(convertToFile(sourceFile))
        }
    }

    def sourceDirs(Object... sourceDirs) {
        this.sourceDirs(Arrays.asList(sourceDirs))
    }

    def sourceDirs(Collection<Object> sourceDirs) {
        sourceDirs.each { sourceDir ->
            this.sourceDirs.add(convertToFile(sourceDir))
        }
    }

    def outputDir(Object outputDir) {
        if (!(outputDir instanceof File))
            outputDir = project.file(outputDir)
        if (this.outputDir == outputDir)
            return
        def oldOutputDir = currentOutputDir()
        this.outputDir = convertToFile(outputDir)
        addSourceDir(oldOutputDir)
    }


    def includeDirs(Object... includeDirs) {
        this.includeDirs(Arrays.asList(includeDirs))
    }

    def includeDirs(Collection<Object> includeDirs) {
        includeDirs.each {
            includeDir ->
                this.includeDirs.add(convertToFile(includeDir))
        }
    }

    def generator(Object gen, Object... args) {
        String options
        if (args == null || args.length < 1)
            options = ''
        else {
            final int n = args.length
            for (int i = 0; i < n; ++i)
                args[i] = args[i].trim()
            options = args.join(',')
        }
        generators.put(String.valueOf(gen).trim(), options)
    }

    def createGenFolder(boolean createGenFolder) {
        if (this.createGenFolder == createGenFolder)
            return
        def oldOutputDir = currentOutputDir()
        this.createGenFolder = createGenFolder
        addSourceDir(oldOutputDir)
    }

    @TaskAction
    def compileThrift(IncrementalTaskInputs inputs) {

        if (!inputs.incremental) {
            compileAll()
            return
        }

        List<File> changedFiles = []
        inputs.outOfDate { change ->
            if (change.file.name.endsWith('.thrift'))
                changedFiles.add(change.file)
        }

        boolean removed = false
        inputs.removed {
            removed = true
        }

        if (removed) {
            if (!outputDir.deleteDir())
                throw new GradleException("Could not delete thrift output directory: ${outputDir.absolutePath}")

            compileAll()
            return
        }

        if (!outputDir.exists() && !outputDir.mkdirs())
            throw new GradleException("Could not create thrift output directory: ${outputDir.absolutePath}")

        changedFiles.each { changedFile ->
            compile(changedFile.absolutePath)
        }
    }

    def compileAll() {
        if (!outputDir.exists() && !outputDir.mkdirs())
            throw new GradleException("Could not create thrift output directory: ${outputDir.absolutePath}")

        // all file
        sourceFiles.addAll(sourceDirs)

        // expand all items.
        Set<String> resolvedSourceFiles = []
        sourceFiles.each {
            sourceFile ->
                if (sourceFile.file) {
                    resolvedSourceFiles.add(sourceFile.absolutePath)
                } else if (sourceFile.directory) {
                    project.fileTree(sourceFile.canonicalPath) {
                        include '**/*.thrift'
                    }.each { foundItem ->
                        resolvedSourceFiles.add(foundItem.absolutePath)
                    }

                } else if (!sourceFile.exists()) {
                    logger.warn("Could not find {}. Will ignore it", sourceFile)
                } else {
                    logger.warn("Unable to handle {}. Will ignore it", sourceFile)
                }
        }

        logger.info("File to be generated for: {}", resolvedSourceFiles)

        resolvedSourceFiles.each {
            compile(it)
        }
    }

    def compile(String source) {
        def cmdLine = [thriftExecutable, createGenFolder ? '-o' : '-out', outputDir.absolutePath]
        generators.each { generator ->
            cmdLine << '--gen'
            String cmd = generator.key.trim()
            String options = generator.value.trim()
            if (!options.isEmpty())
                cmd += ':' + options
            cmdLine << cmd
        }
        includeDirs.each { includeDir ->
            cmdLine << '-I'
            cmdLine << includeDir.absolutePath
        }
        if (recurse) cmdLine << '-r'
        if (nowarn) cmdLine << '-nowarn'
        if (strict) cmdLine << '-strict'
        if (verbose) cmdLine << '-v'
        if (debug) cmdLine << '-debug'
        cmdLine << source

        def result = project.exec {
            commandLine cmdLine
        }

        def exitCode = result.exitValue
        if (exitCode != 0)
            throw new GradleException("Failed to compile ${source}, exit=${exitCode}")
    }

    def makeAsDependency(File oldOutputDir) {
        Task compileJava = project.tasks.getByName(JavaPlugin.COMPILE_JAVA_TASK_NAME);
        if (compileJava == null)
            return

        generators['java'] = ''
        def genJava = currentOutputDir().canonicalFile
        if (genJava == oldOutputDir)
            return

        if (oldOutputDir != null)
            project.sourceSets.main.java.srcDirs -= oldOutputDir
        project.sourceSets.main.java.srcDir genJava.absolutePath

        compileJava.dependsOn this
    }

    private def addSourceDir(File oldOutputDir) {
        if (project.plugins.hasPlugin('java'))
            makeAsDependency(oldOutputDir)
        else {
            project.plugins.whenPluginAdded { plugin ->
                if (plugin instanceof JavaPlugin)
                    makeAsDependency(oldOutputDir)
            }
        }
    }

    def convertToFile(Object item) {
        if (item instanceof File) {
            return item
        }

        def result = new File(item.toString())
        if (result.exists()) {
            return result
        }

        project.file(item)
    }

    private def currentOutputDir() {
        def currentOutputDir = outputDir
        if (currentOutputDir == null)
            return null
        if (createGenFolder)
            currentOutputDir = new File(currentOutputDir, 'gen-java')
        return currentOutputDir
    }
}