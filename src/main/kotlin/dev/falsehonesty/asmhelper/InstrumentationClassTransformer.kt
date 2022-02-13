package dev.falsehonesty.asmhelper

import dev.falsehonesty.asmhelper.printing.LogLevel
import dev.falsehonesty.asmhelper.printing.log
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.CheckClassAdapter
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain


abstract class InstrumentationClassTransformer : ClassFileTransformer {

    private companion object {
        private val pkg = "dev.falsehonesty.asmhelper.".replace(".", "/")
        private val kotlinPkg = "kotlin.".replace(".", "/")
    }

    private val myClassName = this.javaClass.name.replace(".", "/")
    private var calledSetup = false

    protected var exportTransformed = false
    private val exportDir by lazy {
        val file = File("./asmhelper/classes/")
        file.deleteRecursively()
        return@lazy file
    }

    private fun setup() {
        makeTransformers()
    }

    /**
     * This is where you would place all of your asm helper dsl magic
     *
     */
    abstract fun makeTransformers()

    override fun transform(classLoader: ClassLoader?, className: String?, p2: Class<*>?, p3: ProtectionDomain?, basicClass: ByteArray?): ByteArray? {
        if (basicClass == null || className == null) return null

        if (className.startsWith(kotlinPkg) || className.startsWith(pkg) || className.startsWith(myClassName)) {
            return basicClass
        }

        if (!calledSetup) {
            try {
                setup()
            } catch (e: Throwable) {
                log("Exception when setting up transformers: ${e.javaClass.simpleName}")
                e.printStackTrace()
            }
            calledSetup = true
        }

        AsmHelper.classReplacers[className]?.let { classFile ->
            log("Completely replacing $className with data from $classFile.")

            return loadClassResource(classFile)
        }

        val writers = AsmHelper.asmWriters
            .filter { it.className.replace(".", "/") == className }
            .ifEmpty { return basicClass }

        log("Transforming class $className")

        val classReader = ClassReader(basicClass)
        val classNode = ClassNode()
        classReader.accept(classNode, ClassReader.EXPAND_FRAMES)

        writers.forEach {
            log("Applying AsmWriter $it to class $className")

            it.transform(classNode)
        }

        val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        try {
            classNode.accept(classWriter)
        } catch (e: Throwable) {
            log("Exception when transforming $className : ${e.javaClass.simpleName}")
            e.printStackTrace()
        }

        val result = classWriter.toByteArray()

        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        CheckClassAdapter.verify(ClassReader(result), false, printWriter)

        if (stringWriter.buffer.isNotEmpty()) {
            log(stringWriter.toString(), LogLevel.VERBOSE)
        }
        printWriter.close()

        if (exportTransformed) {
            val outFile =
                File(exportDir, className.replace('/', File.separatorChar) + ".class")
            val outDir = outFile.parentFile

            if (!outDir.exists()) {
                outDir.mkdirs()
            }

            if (outFile.exists()) {
                outFile.delete()
            }

            try {
                log(
                    "Saving transformed class \"${className.replace('/', '.')}\" to \"${outFile.absolutePath.replace('\\', '/')}\"",
                    LogLevel.VERBOSE
                )
                outFile.writeBytes(result)
            } catch (ex: IOException) {
                log("Could not save transformed class \"${className.replace('/', '.')}\"", LogLevel.VERBOSE)
                ex.printStackTrace()
            }
        }


        return result
    }

    private fun loadClassResource(name: String): ByteArray {
        return this::class.java.classLoader.getResourceAsStream(name).readBytes()
    }
}
