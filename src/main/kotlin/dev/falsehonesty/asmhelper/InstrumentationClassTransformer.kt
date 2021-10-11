package dev.falsehonesty.asmhelper

import dev.falsehonesty.asmhelper.printing.log
import org.apache.logging.log4j.LogManager
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

abstract class InstrumentationClassTransformer : ClassFileTransformer {

    private companion object {
        private val pkg = "dev.falsehonesty.asmhelper.".replace(".", "/")
        private val kotlinPkg = "kotlin.".replace(".", "/")
    }

    private val myClassName = this.javaClass.name.replace(".", "/")
    private var calledSetup = false

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
            setup()
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


        return classWriter.toByteArray()
    }

    private fun loadClassResource(name: String): ByteArray {
        return this::class.java.classLoader.getResourceAsStream(name).readBytes()
    }
}
