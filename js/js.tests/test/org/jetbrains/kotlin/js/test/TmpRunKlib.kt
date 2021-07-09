/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.test

import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.js.messageCollectorLogger
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.*
import org.jetbrains.kotlin.ir.backend.js.ic.SerializedIcData
import org.jetbrains.kotlin.ir.backend.js.ic.prepareSingleLibraryIcCache
import org.jetbrains.kotlin.ir.backend.js.ic.readIcData
import org.jetbrains.kotlin.ir.backend.js.ic.writeTo
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrFactory
import org.jetbrains.kotlin.js.config.EcmaVersion
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.library.resolver.TopologicalLibraryOrder
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.serialization.js.ModuleKind
import org.jetbrains.kotlin.test.KotlinTestWithEnvironment
import org.junit.Test
import java.io.File

class TmpRunKlib : KotlinTestWithEnvironment() {
    override fun createEnvironment() =
        KotlinCoreEnvironment.createForTests(testRootDisposable, CompilerConfiguration(), EnvironmentConfigFiles.JS_CONFIG_FILES)

    private val fsadKlibs = """
            /home/ab/vcs/kotlin-full-stack-application-demo/shared/build/libs/shared-js-0.1.1.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-json-js/1.2.1/80c5fe5a2d608d0c75b1c6d59a2b8585ee6bd911/kotlinx-serialization-json-jsir-1.2.1.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-js/1.5.0/217834c27f3ba5f28b897fead52238f6f30febb1/kotlinx-coroutines-core-jsir-1.5.0.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-styled/5.3.0-pre.206-kotlin-1.5.10/51d97f575181f0f31a47843f67d9732ebc288f4a/kotlin-styled-5.3.0-pre.206-kotlin-1.5.10.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-react-dom/17.0.2-pre.206-kotlin-1.5.10/c620f8e03c6708fc7ef3b679e2bb56bfcbb802e6/kotlin-react-dom-17.0.2-pre.206-kotlin-1.5.10.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-react/17.0.2-pre.206-kotlin-1.5.10/6c6990bbca42a97d7c9a7722e27265bc64bca94b/kotlin-react-17.0.2-pre.206-kotlin-1.5.10.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-extensions/1.0.1-pre.206-kotlin-1.5.10/bf2d15b5123b33e3569bd06b8625ec79ecb90ca/kotlin-extensions-1.0.1-pre.206-kotlin-1.5.10.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/atomicfu-js/0.16.1/a462e9d32ae4d38851d2c831025981198f10f75b/atomicfu-jsir-0.16.1.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-html-js/0.7.3/32f189139d9200d2c7c8c0b050e907788113609/kotlinx-html-jsir-0.7.3.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-core-js/1.2.1/ddf1ed605747a19c432b66d12a2c2e95dfd68cac/kotlinx-serialization-core-jsir-1.2.1.klib, 
            /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-css-js/1.0.0-pre.206-kotlin-1.5.10/85b4dff094a687e9f64ffa1eae796345b8ad2bfe/kotlin-css-jsir-1.0-SNAPSHOT.klib, 
            /home/ab/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-js/1.5.30-ic/kotlin-stdlib-js-1.5.30-ic.jar, 
            /home/ab/vcs/kotlin-full-stack-application-demo/client/build/classes/kotlin/main
            """.trimIndent()


    private val spaceKlibs = """
        /home/ab/vcs/space/plugins/analytics/analytics-sandbox-web/build/libs/analytics-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/analytics/analytics-web/build/libs/analytics-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-azure/auth-module-azure-web/build/libs/auth-module-azure-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-builtin/auth-module-builtin-web/build/libs/auth-module-builtin-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-github/auth-module-github-web/build/libs/auth-module-github-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-google/auth-module-google-web/build/libs/auth-module-google-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-hub/auth-module-hub-web/build/libs/auth-module-hub-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-ldap/auth-module-ldap-web/build/libs/auth-module-ldap-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-oidc/auth-module-oidc-web/build/libs/auth-module-oidc-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-saml/auth-module-saml-web/build/libs/auth-module-saml-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/blogs/blogs-sandbox-web/build/libs/blogs-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/blogs/blogs-web/build/libs/blogs-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/code/code-sandbox-web/build/libs/code-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pages/pages-sandbox-web/build/libs/pages-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pages/pages-web/build/libs/pages-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/code/code-web/build/libs/code-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/collab/collab-sandbox-web/build/libs/collab-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/collab/collab-web/build/libs/collab-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/fleet/fleet-sandbox-web/build/libs/fleet-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/fleet/fleet-web/build/libs/fleet-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/google-integration/google-integration-web/build/libs/google-integration-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hosting/hosting-sandbox-web/build/libs/hosting-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hosting/hosting-web/build/libs/hosting-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hrm/hrm-sandbox-web/build/libs/hrm-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hrm/hrm-web/build/libs/hrm-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/intellij-shared-indexes/intellij-shared-indexes-web/build/libs/intellij-shared-indexes-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/internal-tools/internal-tools-web/build/libs/internal-tools-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/kb/kb-sandbox-web/build/libs/kb-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/kb/kb-web/build/libs/kb-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/meetings/meetings-sandbox-web/build/libs/meetings-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/meetings/meetings-web/build/libs/meetings-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages/packages-sandbox-web/build/libs/packages-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages/packages-web/build/libs/packages-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-container/packages-container-web/build/libs/packages-container-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-maven/packages-maven-web/build/libs/packages-maven-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-npm/packages-npm-web/build/libs/packages-npm-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-nuget/packages-nuget-web/build/libs/packages-nuget-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pass/pass-web/build/libs/pass-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pipelines/pipelines-sandbox-web/build/libs/pipelines-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pipelines/pipelines-web/build/libs/pipelines-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/planning/planning-sandbox-web/build/libs/planning-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/planning/planning-web/build/libs/planning-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/responsibilities/responsibilities-sandbox-web/build/libs/responsibilities-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/responsibilities/responsibilities-web/build/libs/responsibilities-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/slack-emojis/slack-emojis-web/build/libs/slack-emojis-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/stickers/stickers-sandbox-web/build/libs/stickers-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/stickers/stickers-web/build/libs/stickers-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/tables/tables-sandbox-web/build/libs/tables-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/tables/tables-web/build/libs/tables-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/telekom/telekom-sandbox-web/build/libs/telekom-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/telekom/telekom-web/build/libs/telekom-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/ui-sandbox/ui-sandbox-web/build/libs/ui-sandbox-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/user-agreement/user-agreement-web/build/libs/user-agreement-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/wifi-management/wifi-management-web/build/libs/wifi-management-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages/packages-web-api/build/libs/packages-web-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/app/app-web/build/libs/app-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/analytics/analytics-client/build/libs/analytics-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/analytics/analytics-common/build/libs/analytics-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-azure/auth-module-azure-client/build/libs/auth-module-azure-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-builtin/auth-module-builtin-client/build/libs/auth-module-builtin-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-github/auth-module-github-client/build/libs/auth-module-github-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-google/auth-module-google-client/build/libs/auth-module-google-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-hub/auth-module-hub-client/build/libs/auth-module-hub-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-ldap/auth-module-ldap-client/build/libs/auth-module-ldap-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-oidc/auth-module-oidc-client/build/libs/auth-module-oidc-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-saml/auth-module-saml-client/build/libs/auth-module-saml-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/blogs/blogs-client/build/libs/blogs-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/code/code-client/build/libs/code-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/collab/collab-client/build/libs/collab-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/fleet/fleet-client/build/libs/fleet-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/google-integration/google-integration-client/build/libs/google-integration-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hosting/hosting-client/build/libs/hosting-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hrm/hrm-client/build/libs/hrm-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/intellij-shared-indexes/intellij-shared-indexes-client/build/libs/intellij-shared-indexes-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/internal-tools/internal-tools-client/build/libs/internal-tools-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/kb/kb-client/build/libs/kb-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/meetings/meetings-client/build/libs/meetings-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages/packages-client/build/libs/packages-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-container/packages-container-client/build/libs/packages-container-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-maven/packages-maven-client/build/libs/packages-maven-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-npm/packages-npm-client/build/libs/packages-npm-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-nuget/packages-nuget-client/build/libs/packages-nuget-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pages/pages-client/build/libs/pages-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pass/pass-client/build/libs/pass-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pipelines/pipelines-client/build/libs/pipelines-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/planning/planning-client/build/libs/planning-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/responsibilities/responsibilities-client/build/libs/responsibilities-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/slack-emojis/slack-emojis-client/build/libs/slack-emojis-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/stickers/stickers-client/build/libs/stickers-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/tables/tables-client/build/libs/tables-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/telekom/telekom-client/build/libs/telekom-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/ui-sandbox/ui-sandbox-client/build/libs/ui-sandbox-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/user-agreement/user-agreement-client/build/libs/user-agreement-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/wifi-management/wifi-management-client/build/libs/wifi-management-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/app/app-state/build/libs/app-state-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/client/build/libs/client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-builtin/auth-module-builtin-common/build/libs/auth-module-builtin-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/code/code-common/build/libs/code-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/fleet/fleet-common/build/libs/fleet-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hrm/hrm-common/build/libs/hrm-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/internal-tools/internal-tools-web/internal-tools-web-core/build/libs/internal-tools-web-core-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/internal-tools/internal-tools-common/build/libs/internal-tools-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/meetings/meetings-common/build/libs/meetings-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pass/pass-common/build/libs/pass-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pipelines/pipelines-api/build/libs/pipelines-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pipelines/pipelines-common/build/libs/pipelines-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/planning/planning-common/build/libs/planning-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/responsibilities/responsibilities-common/build/libs/responsibilities-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/stickers/stickers-common/build/libs/stickers-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/tables/tables-common/build/libs/tables-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/telekom/telekom-common/build/libs/telekom-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/ui-sandbox/ui-sandbox-common/build/libs/ui-sandbox-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/wifi-management/wifi-management-common/build/libs/wifi-management-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-app/platform-app-web/build/libs/platform-app-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-web/build/libs/platform-web-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-space-validation/build/libs/platform-space-validation-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-metrics/platform-metrics-app/build/libs/platform-metrics-app-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-metrics/platform-metrics-lightweight/build/libs/platform-metrics-lightweight-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/analytics/analytics-api/build/libs/analytics-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-azure/auth-module-azure-api/build/libs/auth-module-azure-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-builtin/auth-module-builtin-api/build/libs/auth-module-builtin-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-github/auth-module-github-api/build/libs/auth-module-github-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-google/auth-module-google-api/build/libs/auth-module-google-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-hub/auth-module-hub-api/build/libs/auth-module-hub-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-ldap/auth-module-ldap-api/build/libs/auth-module-ldap-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-oidc/auth-module-oidc-api/build/libs/auth-module-oidc-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/auth-module-saml/auth-module-saml-api/build/libs/auth-module-saml-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/blogs/blogs-api/build/libs/blogs-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/code/code-api/build/libs/code-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/collab/collab-api/build/libs/collab-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/fleet/fleet-api/build/libs/fleet-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/google-integration/google-integration-api/build/libs/google-integration-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hosting/hosting-api/build/libs/hosting-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/hrm/hrm-api/build/libs/hrm-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/intellij-shared-indexes/intellij-shared-indexes-api/build/libs/intellij-shared-indexes-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/internal-tools/internal-tools-api/build/libs/internal-tools-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/kb/kb-api/build/libs/kb-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/meetings/meetings-api/build/libs/meetings-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-container/packages-container-api/build/libs/packages-container-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-maven/packages-maven-api/build/libs/packages-maven-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-npm/packages-npm-api/build/libs/packages-npm-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages-nuget/packages-nuget-api/build/libs/packages-nuget-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/packages/packages-api/build/libs/packages-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pages/pages-api/build/libs/pages-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pass/pass-api/build/libs/pass-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/planning/planning-api/build/libs/planning-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/responsibilities/responsibilities-api/build/libs/responsibilities-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/slack-emojis/slack-emojis-api/build/libs/slack-emojis-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/stickers/stickers-api/build/libs/stickers-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/tables/tables-api/build/libs/tables-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/telekom/telekom-api/build/libs/telekom-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/ui-sandbox/ui-sandbox-api/build/libs/ui-sandbox-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/user-agreement/user-agreement-api/build/libs/user-agreement-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/wifi-management/wifi-management-api/build/libs/wifi-management-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/client-api/build/libs/client-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/common/build/libs/common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-app/platform-app-state/build/libs/platform-app-state-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-client/build/libs/platform-client-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-client-http-old/build/libs/platform-client-http-old-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-metrics/platform-metrics-client-api/build/libs/platform-metrics-client-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-api/build/libs/platform-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-product-metrics/platform-product-metrics-events/build/libs/platform-product-metrics-events-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-product-metrics/platform-product-metrics-core/build/libs/platform-product-metrics-core-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-metrics/platform-metrics-core/build/libs/platform-metrics-core-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-runtime-html/build/libs/platform-runtime-html-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-bundled-icons/build/libs/platform-bundled-icons-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/tools/font-icons-runtime/build/libs/font-icons-runtime-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-runtime/build/libs/platform-runtime-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-coroutines-extra/build/libs/libraries-coroutines-extra-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/plugins/pipelines/pipelines-common-api/build/libs/pipelines-common-api-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-service-messages/build/libs/libraries-service-messages-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-basics/build/libs/libraries-basics-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/platform/platform-common/build/libs/platform-common-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-io/build/libs/libraries-io-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-collections/build/libs/libraries-collections-js-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-klogging/build/libs/libraries-klogging-js-0.1-SNAPSHOT.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-client-json-js/1.6.0/8159611aaa78ff7fcecc93ceff4544fcdbc1435c/ktor-client-json-jsir-1.6.0.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-client-js/1.6.0/a3ee06e95ffaa45dddc7c3bef3257146e66d11/ktor-client-js-jsir-1.6.0.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-client-core-js/1.6.0/98a4a1ea6f097966e4a7145c8c83e8d389990b3b/ktor-client-core-jsir-1.6.0.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-http-cio-js/1.6.0/b66eba584e7926e2e95ae8f304cd91dcc1624c44/ktor-http-cio-jsir-1.6.0.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-http-js/1.6.0/9ff4a9fd742d6acbbcf55c72373a75c21c696fa4/ktor-http-jsir-1.6.0.klib,
        /home/ab/vcs/space/services/telekom-sfu/telekom-sfu-common/build/libs/telekom-sfu-common-js-0.1-SNAPSHOT.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-utils-js/1.6.0/6c35b8638c001e0628e0cd237e589414ae2408a4/ktor-utils-jsir-1.6.0.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-io-js/1.6.0/e2331cfbcfe44ccc836390a90f223c5ea7f7621a/ktor-io-jsir-1.6.0.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-js/1.5.0-RC/63c7672fb20a2f637a2bc9caef2fb1bf2aba010a/kotlinx-coroutines-core-jsir-1.5.0-RC.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/atomicfu-js/0.16.1/a462e9d32ae4d38851d2c831025981198f10f75b/atomicfu-jsir-0.16.1.klib,
        /home/ab/vcs/space/libraries/libraries-mediasoup-client/build/libs/libraries-mediasoup-client-0.1-SNAPSHOT.klib,
        /home/ab/vcs/space/libraries/libraries-w3c-webrtc/build/libs/libraries-w3c-webrtc-0.1-SNAPSHOT.klib,
        /home/ab/.m2/repository/org/jetbrains/kotlin/kotlin-test-js/1.5.30-ic-report-klibs/kotlin-test-js-1.5.30-ic-report-klibs.jar,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-styled/5.3.0-pre.213-kotlin-1.5.20/2f6e4f36498cca77f71387d9b79a3f6573aa50c6/kotlin-styled-5.3.0-pre.213-kotlin-1.5.20.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-react-dom/17.0.2-pre.213-kotlin-1.5.20/788f6191ce20b98d3c7a34f7951dca7e80c32d7c/kotlin-react-dom-17.0.2-pre.213-kotlin-1.5.20.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-react/17.0.2-pre.213-kotlin-1.5.20/cc354643d41f83818614b82d823eca1b7c31a014/kotlin-react-17.0.2-pre.213-kotlin-1.5.20.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-extensions/1.0.1-pre.213-kotlin-1.5.20/3da1ab227b5df71a1df164f5fec47eb637c89969/kotlin-extensions-1.0.1-pre.213-kotlin-1.5.20.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin-wrappers/kotlin-css-js/1.0.0-pre.213-kotlin-1.5.20/75a7df15343249a493dffe00d30be538104b4e7f/kotlin-css-jsir-1.0.0-pre.213-kotlin-1.5.20.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-html-js/0.7.3/65c5f8cf360a015272fe8abfb82bc5815b39dc00/kotlinx-html-jsir-0.7.3.klib,
        /home/ab/.gradle/caches/modules-2/files-2.1/org.jetbrains/markdown-js/0.2.4/738344f094e36c35258af57e74ba495e70fbb03e/markdown-jsir-0.2.4.klib,
        /home/ab/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-js/1.5.30-ic-report-klibs/kotlin-stdlib-js-1.5.30-ic-report-klibs.jar,
        /home/ab/vcs/space/all-js/build/classes/kotlin/js/main
    """.trimIndent()

    @Test
    fun testFsadIC() {
        icPipeline(fsadKlibs, "/home/ab/tmp/cache-fsad")
    }

    @Test
    fun testFsadOrig() {
        origPipeline(fsadKlibs)
    }

    @Test
    fun testSpaceIC() {
        icPipeline(spaceKlibs, "/home/ab/tmp/cache-space")
    }

    @Test
    fun testSpaceOrig() {
        origPipeline(spaceKlibs)
    }


    private fun icPipeline(klibs: String, cachePath: String) {
        val allKlibPaths = klibs.split(",").map { it.trim() }
        val resolvedLibraries = jsResolveLibraries(allKlibPaths, emptyList(), messageCollectorLogger(MessageCollector.NONE))

        val configuration = environment.configuration.copy()
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "main")
        configuration.put(JSConfigurationKeys.MODULE_KIND, ModuleKind.UMD)
        configuration.put(JSConfigurationKeys.TARGET, EcmaVersion.v5)

        val config = JsConfig(
            project,
            configuration,
            CompilerEnvironment,
            BasicBoxTest.METADATA_CACHE,
            (JsConfig.JS_STDLIB + JsConfig.JS_KOTLIN_TEST).toSet()
        )

        val mainLib = resolvedLibraries.getFullList().find { it.libraryFile.absolutePath == File(allKlibPaths.last()).absolutePath }!!
        val mainModule = MainModule.Klib(mainLib)

        val icCache = time("building caches", 0, 1) {
            val map = mutableMapOf<String, SerializedIcData>()

            for (klibPath in resolvedLibraries.getFullList(TopologicalLibraryOrder).map { it.libraryFile.absolutePath }) {

                if (klibPath == mainModule.lib.libraryFile.absolutePath) continue

                val cacheName = File(klibPath).name + "-" + klibPath.hashCode()

                val cacheFile = File(File(cachePath), cacheName)

                val start = System.currentTimeMillis()

                val icData = if (cacheFile.exists()) {
                    println("Loading ic cache for ${klibPath}")
                    cacheFile.readIcData()
                } else {
                    println("Building cache for ${klibPath}")

                    Timer.run("prepareSingleLibraryIcCache") {
                        prepareSingleLibraryIcCache(
                            project = project,
                            analyzer = AnalyzerWithCompilerReport(config.configuration),
                            configuration = config.configuration,
                            library = resolvedLibraries.getFullList()
                                .single { it.libraryFile.absolutePath == File(klibPath).absolutePath },
                            dependencies = resolvedLibraries.filterRoots { it.library.libraryFile.absolutePath == File(klibPath).absolutePath },
                            icCache = map
                        )
                    }.also {
                        cacheFile.mkdirs()
                        it.writeTo(cacheFile)
                    }
                }

                println("In ${System.currentTimeMillis() - start}ms")

                map[klibPath] = icData
            }

            map
        }


        println("Compiling")

        val compiledModule = time("compiling") {
            val irFactory = PersistentIrFactory()
            compile(
                project = config.project,
                mainModule = mainModule,
                analyzer = AnalyzerWithCompilerReport(config.configuration),
                configuration = config.configuration,
                phaseConfig = PhaseConfig(jsPhases),
                irFactory = irFactory,
                allDependencies = resolvedLibraries,
                friendDependencies = emptyList(),
                mainArguments = null,
                propertyLazyInitialization = false,
                generateFullJs = false,
                generateDceJs = true,
                lowerPerModule = true,
                useStdlibCache = true,
                icCache = icCache,
            )
        }
    }

    private fun origPipeline(klibs: String) {
        val allKlibPaths = klibs.split(",").map { it.trim() }
        val resolvedLibraries = jsResolveLibraries(allKlibPaths, emptyList(), messageCollectorLogger(MessageCollector.NONE))

        val configuration = environment.configuration.copy()
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "main")
        configuration.put(JSConfigurationKeys.MODULE_KIND, ModuleKind.UMD)
        configuration.put(JSConfigurationKeys.TARGET, EcmaVersion.v5)

        val config = JsConfig(
            project,
            configuration,
            CompilerEnvironment,
            BasicBoxTest.METADATA_CACHE,
            (JsConfig.JS_STDLIB + JsConfig.JS_KOTLIN_TEST).toSet()
        )

        val mainLib = resolvedLibraries.getFullList().find { it.libraryFile.absolutePath == File(allKlibPaths.last()).absolutePath }!!
        val mainModule = MainModule.Klib(mainLib)

        println("Compiling")

        val compiledModule = time("compiling") {
            val irFactory = IrFactoryImpl//PersistentIrFactory()
            compile(
                project = config.project,
                mainModule = mainModule,
                analyzer = AnalyzerWithCompilerReport(config.configuration),
                configuration = config.configuration,
                phaseConfig = PhaseConfig(jsPhases),
                irFactory = irFactory,
                allDependencies = resolvedLibraries,
                friendDependencies = emptyList(),
                mainArguments = null,
                propertyLazyInitialization = false,
                generateFullJs = false,
                generateDceJs = true,
            )
        }
    }
}

fun <T> time(name: String, coldRuns: Int = 0, hotRuns: Int = 1, fn: () -> T): T {

    for (i in 1..coldRuns) {
        fn()
        println(".")
    }

    val start = System.currentTimeMillis()
    Timer.clear()

    for (i in 2..hotRuns) {
        fn()
        println("!")
    }

    try {
        return fn()

    } finally {
        println("!")
        println("$name took ${(System.currentTimeMillis() - start) / hotRuns}ms")
        Timer.report()
    }
}