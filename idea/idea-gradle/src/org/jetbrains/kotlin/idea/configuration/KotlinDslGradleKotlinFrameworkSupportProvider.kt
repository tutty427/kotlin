/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.configuration

import com.intellij.framework.FrameworkTypeEx
import com.intellij.framework.addSupport.FrameworkSupportInModuleConfigurable
import com.intellij.framework.addSupport.FrameworkSupportInModuleProvider
import com.intellij.ide.util.frameworkSupport.FrameworkSupportModel
import com.intellij.openapi.externalSystem.model.project.ProjectId
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModifiableModelsProvider
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.idea.KotlinIcons
import org.jetbrains.kotlin.idea.configuration.GroovyBuildScriptManipulator.Companion.addLastExpressionInBlockIfNeeded
import org.jetbrains.kotlin.idea.configuration.GroovyBuildScriptManipulator.Companion.getBlockOrCreate
import org.jetbrains.kotlin.idea.configuration.GroovyBuildScriptManipulator.Companion.getBlockOrPrepend
import org.jetbrains.kotlin.idea.configuration.KotlinBuildScriptManipulator.Companion.GSK_KOTLIN_VERSION_PROPERTY_NAME
import org.jetbrains.kotlin.idea.configuration.KotlinBuildScriptManipulator.Companion.getCompileDependencySnippet
import org.jetbrains.kotlin.idea.configuration.KotlinBuildScriptManipulator.Companion.getKotlinDependencySnippet
import org.jetbrains.kotlin.idea.configuration.KotlinBuildScriptManipulator.Companion.getKotlinGradlePluginClassPathSnippet
import org.jetbrains.kotlin.idea.core.copied
import org.jetbrains.kotlin.idea.refactoring.toPsiFile
import org.jetbrains.kotlin.idea.versions.*
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.plugins.gradle.frameworkSupport.BuildScriptDataBuilder
import org.jetbrains.plugins.gradle.frameworkSupport.KotlinDslGradleFrameworkSupportProvider
import org.jetbrains.plugins.gradle.service.project.wizard.GradleModuleBuilder
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import java.io.File
import javax.swing.Icon
import javax.swing.JComponent

abstract class KotlinDslGradleKotlinFrameworkSupportProvider(
    val frameworkTypeId: String,
    val displayName: String,
    val frameworkIcon: Icon,
    private val withPluginsBlock: Boolean
) : KotlinDslGradleFrameworkSupportProvider() {
    companion object {
        private var Module.gradleModuleBuilder: GradleModuleBuilder? by UserDataProperty(Key.create("GRADLE_MODULE_BUILDER"))
    }

    override fun getFrameworkType(): FrameworkTypeEx = object : FrameworkTypeEx(frameworkTypeId) {
        override fun getIcon(): Icon = frameworkIcon
        override fun getPresentableName(): String = displayName
        override fun createProvider(): FrameworkSupportInModuleProvider = this@KotlinDslGradleKotlinFrameworkSupportProvider
    }

    override fun createConfigurable(model: FrameworkSupportModel): FrameworkSupportInModuleConfigurable {
        return object : FrameworkSupportInModuleConfigurable() {
            override fun createComponent(): JComponent? {
                return this@KotlinDslGradleKotlinFrameworkSupportProvider.createComponent()
            }

            override fun addSupport(
                module: Module,
                rootModel: ModifiableRootModel,
                modifiableModelsProvider: ModifiableModelsProvider
            ) {
                val buildScriptData = GradleModuleBuilder.getBuildScriptData(module)
                if (buildScriptData != null) {
                    val builder = model.moduleBuilder
                    val projectId = if (builder is GradleModuleBuilder)
                        builder.projectId
                    else
                        ProjectId(null, module.name, null)

                    try {
                        module.gradleModuleBuilder = builder as? GradleModuleBuilder
                        this@KotlinDslGradleKotlinFrameworkSupportProvider.addSupport(
                                projectId,
                                module,
                                rootModel,
                                modifiableModelsProvider,
                                buildScriptData
                        )
                    } finally {
                        module.gradleModuleBuilder = null
                    }
                }
            }
        }
    }

    private fun findSettingsGradleFile(module: Module): VirtualFile? {
        val contentEntryPath = module.gradleModuleBuilder?.contentEntryPath ?: return null
        if (contentEntryPath.isEmpty()) return null
        val contentRootDir = File(contentEntryPath)
        val modelContentRootDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(contentRootDir) ?: return null
        return modelContentRootDir.findChild(GradleConstants.SETTINGS_FILE_NAME)
                ?: module.project.baseDir.findChild(GradleConstants.SETTINGS_FILE_NAME)
    }

    // Circumvent write actions and modify the file directly
    // TODO: Get rid of this hack when IDEA API allows manipulation of settings script similarly to the main script itself
    private fun addRepositoryToSettings(repository: String, module: Module) {
        val settingsFile = findSettingsGradleFile(module) ?: return
        val project = module.project
        val settingsPsi = settingsFile.toPsiFile(project) as? GroovyFile ?: return
        val settingsPsiCopy = settingsPsi.copied().apply {
            getBlockOrPrepend("pluginManagement")
                .getBlockOrCreate("repositories")
                .addLastExpressionInBlockIfNeeded(repository)
            CodeStyleManager.getInstance(project).reformat(this)
        }
        VfsUtil.saveText(settingsFile, settingsPsiCopy.text)
    }

    override fun addSupport(
        projectId: ProjectId,
        module: Module,
        rootModel: ModifiableRootModel,
        modifiableModelsProvider: ModifiableModelsProvider,
        buildScriptData: BuildScriptDataBuilder
    ) {
        var kotlinVersion = bundledRuntimeVersion()
        val additionalRepository = getRepositoryForVersion(kotlinVersion)
        if (isSnapshot(bundledRuntimeVersion())) {
            kotlinVersion = LAST_SNAPSHOT_VERSION
        }

        if (additionalRepository != null) {
            val repository = additionalRepository.toKotlinRepositorySnippet()
            if (withPluginsBlock) {
                addRepositoryToSettings(repository, module)
            } else {
                buildScriptData.addBuildscriptRepositoriesDefinition(repository)
            }
            buildScriptData.addRepositoriesDefinition("mavenCentral()")
            buildScriptData.addRepositoriesDefinition(repository)
        }

        if (withPluginsBlock) {
            buildScriptData
                .addPluginDefinitionInPluginsGroup(getPluginDefinition() + " version \"$kotlinVersion\"")
                .addDependencyNotation(getRuntimeLibrary(rootModel))
        }
        else {
            buildScriptData
                .addPropertyDefinition("val $GSK_KOTLIN_VERSION_PROPERTY_NAME: String by extra")
                .addPluginDefinition(getPluginDefinition())
                .addBuildscriptRepositoriesDefinition("mavenCentral()")
                .addRepositoriesDefinition("mavenCentral()")
                // TODO: in gradle > 4.1 this could be single declaration e.g. 'val kotlin_version: String by extra { "1.1.11" }'
                .addBuildscriptPropertyDefinition("var $GSK_KOTLIN_VERSION_PROPERTY_NAME: String by extra\n    $GSK_KOTLIN_VERSION_PROPERTY_NAME = \"$kotlinVersion\"")
                .addDependencyNotation(getRuntimeLibrary(rootModel))
                .addBuildscriptDependencyNotation(getKotlinGradlePluginClassPathSnippet())
        }
    }

    private fun RepositoryDescription.toKotlinRepositorySnippet() = "maven { setUrl(\"$url\") }"

    protected abstract fun getRuntimeLibrary(rootModel: ModifiableRootModel): String

    protected abstract fun getPluginDefinition(): String
}

class KotlinDslGradleKotlinJavaFrameworkSupportProvider :
    KotlinDslGradleKotlinFrameworkSupportProvider("KOTLIN", "Kotlin (Java)", KotlinIcons.SMALL_LOGO, true) {

    override fun getPluginDefinition() = "kotlin(\"jvm\")"

    override fun getRuntimeLibrary(rootModel: ModifiableRootModel) =
        "compile(${getKotlinDependencySnippet(getStdlibArtifactId(rootModel.sdk, bundledRuntimeVersion()))})"

    override fun addSupport(
        projectId: ProjectId,
        module: Module,
        rootModel: ModifiableRootModel,
        modifiableModelsProvider: ModifiableModelsProvider,
        buildScriptData: BuildScriptDataBuilder
    ) {
        super.addSupport(projectId, module, rootModel, modifiableModelsProvider, buildScriptData)
        val jvmTarget = getDefaultJvmTarget(rootModel.sdk, bundledRuntimeVersion())
        if (jvmTarget != null) {
            buildScriptData
                .addImport("import org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
                .addOther("tasks.withType<KotlinCompile> {\n    kotlinOptions.jvmTarget = \"1.8\"\n}\n")
        }
    }
}

class KotlinDslGradleKotlinJSFrameworkSupportProvider :
    KotlinDslGradleKotlinFrameworkSupportProvider("KOTLIN_JS", "Kotlin (JavaScript)", KotlinIcons.JS, false) {

    override fun getPluginDefinition(): String = "plugin(\"${KotlinJsGradleModuleConfigurator.KOTLIN_JS}\")"

    override fun getRuntimeLibrary(rootModel: ModifiableRootModel) =
        getCompileDependencySnippet(KOTLIN_GROUP_ID, MAVEN_JS_STDLIB_ID.removePrefix("kotlin-"))
}
