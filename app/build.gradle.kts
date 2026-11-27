import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Foundation 1.9.0 mistakes output-only caret affinity changes for source edits.
// Keep native handle drawing/gestures; observe raw edits only. See ADR 0017.
abstract class ComposeRawTextObserverFix : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) =
        classData.className == "androidx.compose.foundation.text.input.internal.selection.TextFieldSelectionState"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var patchedCalls = 0

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "observeTextChanges\$lambda\$18" || descriptor !=
                    "(Landroidx/compose/foundation/text/input/internal/selection/TextFieldSelectionState;)Landroidx/compose/foundation/text/input/TextFieldCharSequence;") return delegate
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && !isInterface &&
                            owner == "androidx/compose/foundation/text/input/internal/TransformedTextFieldState" &&
                            name == "getVisualText" && descriptor == "()Landroidx/compose/foundation/text/input/TextFieldCharSequence;") {
                            patchedCalls++
                            super.visitMethodInsn(opcode, owner, "getUntransformedText", descriptor, false)
                        } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }

            override fun visitEnd() {
                check(patchedCalls == 1) { "Foundation caret fix no longer matches exactly once; review ADR 0017 before changing dependencies." }
                super.visitEnd()
            }
        }
}

check(libs.versions.compose.bom.get() == "2025.08.01") {
    "Review the Foundation 1.9.0 caret observer fix (ADR 0017) before upgrading Compose."
}
androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(ComposeRawTextObserverFix::class.java, InstrumentationScope.ALL) {}
    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
}

android {
    namespace = "io.github.xrisium.makudon"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.xrisium.makudon"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
        testInstrumentationRunner = "io.github.xrisium.makudon.WorkspaceInstrumentation"
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }
    // Every supported language must be available for offline, in-app switching.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasks)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
