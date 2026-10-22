package local.dshx.app

import org.json.JSONObject
import android.content.ClipData
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import android.util.AndroidRuntimeException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import android.content.ContextWrapper
import android.content.res.AssetManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Implements(WebView::class)
    class MissingWebView {
        companion object {
            @JvmStatic @Implementation
            fun setWebContentsDebuggingEnabled(enabled: Boolean) {
                throw AndroidRuntimeException("No WebView installed")
            }
            @JvmStatic @Implementation
            fun getCurrentWebViewPackage(): android.content.pm.PackageInfo? = null
        }
    }

    @Test
    @Config(sdk = [26], shadows = [MissingWebView::class])
    fun nativePageOpensWithoutAnInstalledWebView() {
        val page = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            assertFalse(page.get().isFinishing)
        } finally { page.destroy() }
    }

    private val contents = linkedMapOf(
        "bin/node" to "node".toByteArray(),
        "start.mjs" to "start".toByteArray(),
        "plugins.mjs" to "plugins".toByteArray(),
        "phone.mjs" to "phone".toByteArray(),
        "assistant.mjs" to "assistant".toByteArray(),
        "lib/node_modules/@deepseek-ai/dsh/lib/bin.js" to "dsh".toByteArray(),
        "lib/node_modules/pnpm/bin/pnpm.cjs" to "pnpm".toByteArray(),
        "lib/node_modules/pnpm/dist/pnpm.cjs" to "pnpm code".toByteArray(),
        "lib/node_modules/npm/bin/npm-cli.js" to "npm".toByteArray(),
        "lib/node_modules/npm/lib/cli.js" to "npm code".toByteArray(),
        "android.patch.yml" to "patch".toByteArray(),
        "guard.mjs" to "guard".toByteArray(),
        "native.node" to "native".toByteArray()
    )

    private fun archive(files: Map<String, ByteArray> = contents): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun manifest(zip: ByteArray, files: Map<String, ByteArray> = contents, version: String = "test-1"): CoreStore.Manifest {
        val entries = files.mapValues { (name, bytes) -> CoreStore.Entry(bytes.size.toLong(), hash(bytes), name == "bin/node") }
        return CoreStore.Manifest(version, "x86_64", hash(zip), zip.size.toLong(), entries)
    }

    private fun hash(bytes: ByteArray) = CoreStore.sha256(ByteArrayInputStream(bytes))
    private fun install(store: CoreStore, zip: ByteArray, manifest: CoreStore.Manifest = manifest(zip)) =
        store.install(manifest, { ByteArrayInputStream(zip) }, Long.MAX_VALUE, { false }, { _, _ -> })

    @Test fun installsVerifiedFilesAndMarksOnlyCompleteCore() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash("abc".toByteArray()))
        val store = CoreStore(folder.root)
        val zip = archive()
        val file = install(store, zip)
        assertEquals(file, store.current(manifest(zip)))
        assertEquals("dsh", File(file, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js").readText())
        assertTrue(File(file, "bin/node").canExecute())
        assertFalse(File(folder.root, ".archive").exists())
        assertFalse(File(folder.root, ".install").exists())
    }

    @Test fun existingCoreDoesNotReadArchiveAgain() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val installed = install(store, zip)
        assertEquals(installed, store.install(manifest(zip), { error("must not open") }, 0, { false }, { _, _ -> }))
    }

    @Test fun normalStartupReusesTheInstallReceiptWithoutScanningTheInventory() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val metadata = manifest(zip)
        val installed = install(store, zip)
        val entries = object : Map<String, CoreStore.Entry> by metadata.entries {
            override val keys: Set<String> get() = error("Normal startup must not scan the inventory")
            override val values: Collection<CoreStore.Entry> get() = error("Normal startup must not hash all files")
        }
        assertEquals(installed, CoreStore(folder.root).installed(metadata.copy(entries = entries)))
        assertNull(store.installed(metadata.copy(archiveHash = "0".repeat(64))))
        File(installed, "bin/node").delete()
        assertNull(store.installed(metadata))
    }

    @Test fun detectsModifiedInstalledFiles() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val installed = install(store, zip)
        File(installed, "bin/node").writeText("fake")
        assertNull(store.current(manifest(zip)))
    }

    @Test fun missingPluginLauncherRequiresRepairWithoutScanningOtherFiles() {
        for (name in listOf("start.mjs", "plugins.mjs", "phone.mjs", "lib/node_modules/pnpm/dist/pnpm.cjs", "lib/node_modules/npm/lib/cli.js")) {
            val store = CoreStore(folder.newFolder())
            val zip = archive()
            val installed = install(store, zip)
            assertTrue(File(installed, name).delete())
            assertNull(store.installed(manifest(zip)))
            assertEquals(installed, install(store, zip))
            assertTrue(File(installed, name).isFile)
        }
    }

    @Test fun rejectsAndRepairsAddedCoreFilesWithoutChangingWorkspace() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val installed = install(store, zip)
        val extra = File(installed, "lib/node_modules/extra/index.js").apply { parentFile!!.mkdirs(); writeText("injected") }
        val note = File(folder.root, "workspace/note.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        assertNull(store.current(manifest(zip)))
        assertEquals(installed, install(store, zip))
        assertFalse(extra.exists())
        assertEquals("keep", note.readText())
        assertNotNull(store.current(manifest(zip)))
    }

    @Test fun rejectsRegisteredFileSymlinkEvenWhenBytesMatch() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val installed = install(store, zip)
        val original = File(installed, "guard.mjs")
        val outside = folder.newFile("outside-guard.mjs").apply { writeText("guard") }
        assertTrue(original.delete())
        Files.createSymbolicLink(original.toPath(), outside.toPath())
        assertNull(store.current(manifest(zip)))
        install(store, zip)
        assertFalse(Files.isSymbolicLink(original.toPath()))
        assertEquals("guard", outside.readText())
    }

    @Test fun repairsLinkedCoreDirectoryWithoutFollowingIt() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val installed = install(store, zip)
        val outside = File(folder.root, "outside-core")
        assertTrue(installed.renameTo(outside))
        Files.createSymbolicLink(installed.toPath(), outside.toPath())
        assertNull(store.current(manifest(zip)))
        install(store, zip)
        assertFalse(Files.isSymbolicLink(installed.toPath()))
        assertEquals("node", File(outside, "bin/node").readText())
        assertNotNull(store.current(manifest(zip)))
    }

    @Test fun repairsLinkedStagingAndArchiveWithoutTouchingTheirTargets() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val outside = folder.newFolder("outside-staging")
        File(outside, "keep.txt").writeText("keep")
        val other = folder.newFile("outside-archive").apply { writeText("keep archive") }
        Files.createSymbolicLink(File(folder.root, ".install").toPath(), outside.toPath())
        Files.createSymbolicLink(File(folder.root, ".archive").toPath(), other.toPath())
        install(store, zip)
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertEquals("keep archive", other.readText())
        assertNotNull(store.current(manifest(zip)))
    }

    @Test fun changedBuildWithSameVersionPreservesPreviousCore() {
        val store = CoreStore(folder.root)
        val first = archive()
        val old = install(store, first)
        val changed = contents + ("guard.mjs" to "new guard".toByteArray())
        val second = archive(changed)
        val next = install(store, second, manifest(second, changed))
        assertNotEquals(old, next)
        assertNotNull(store.current(manifest(first)))
        assertTrue(old.exists())
    }

    @Test fun installedVerificationCanBeCancelledBetweenReads() {
        val store = CoreStore(folder.root)
        val zip = archive()
        install(store, zip)
        var checks = 0
        assertThrows(InterruptedException::class.java) { store.current(manifest(zip)) { ++checks > 3 } }
        assertNotNull(store.current(manifest(zip)))
    }

    @Test fun idleStopIsIdempotentAndAllowsFutureStart() {
        val engine = Engine(org.robolectric.RuntimeEnvironment.getApplication())
        assertFalse(engine.stop())
        assertFalse(engine.stop())
        assertFalse(engine.active)
        assertFalse(engine.state.busy)
    }

    @Test fun cancellingBeforePreparationDoesNotInstallDuringCleanup() {
        val jobs = mutableListOf<Runnable>()
        var assetReads = 0
        val files = folder.newFolder("cancelled-engine")
        val context = object : ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getAssets(): AssetManager {
                assetReads++
                error("Cancelled preparation must not read or reinstall the core")
            }
        }
        val engine = Engine(context, Executor { jobs.add(it) })
        engine.start {}
        assertTrue(engine.stop())
        jobs.single().run()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, assetReads)
        assertFalse(engine.active)
        assertEquals("已停止", engine.state.phase)
        assertFalse(EngineRecovery(files).needed)
    }

    @Test fun repairsDamagedVersion() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val installed = install(store, zip)
        File(installed, "bin/node").delete()
        install(store, zip)
        assertNotNull(store.current(manifest(zip)))
    }

    @Test fun rejectsWrongArchiveHashWithoutActivating() {
        val store = CoreStore(folder.root)
        val zip = archive()
        assertThrows(IllegalArgumentException::class.java) { install(store, zip, manifest(zip).copy(archiveHash = "0".repeat(64))) }
        assertFalse(File(folder.root, "test-1").exists())
    }

    @Test fun rejectsChangedFileEvenIfArchiveHashMatches() {
        val good = archive()
        val bad = archive(contents + ("bin/node" to "evil".toByteArray()))
        val store = CoreStore(folder.root)
        val metadata = manifest(good).copy(archiveHash = hash(bad), archiveSize = bad.size.toLong())
        assertThrows(IllegalArgumentException::class.java) { install(store, bad, metadata) }
        assertNull(store.current(metadata))
    }

    @Test fun rejectsMissingFiles() {
        val bad = archive(contents - "guard.mjs")
        assertThrows(IllegalArgumentException::class.java) { install(CoreStore(folder.root), bad) }
    }

    @Test fun rejectsUndeclaredFiles() {
        val bad = archive(contents + ("extra" to byteArrayOf(1)))
        assertThrows(IllegalStateException::class.java) { install(CoreStore(folder.root), bad) }
    }

    @Test fun rejectsParentTraversalEvenWhenDeclaredInManifest() {
        val badFiles = contents + ("../escape" to byteArrayOf(1))
        val zip = archive(badFiles)
        assertThrows(IllegalArgumentException::class.java) { install(CoreStore(folder.root), zip, manifest(zip, badFiles)) }
        assertFalse(File(folder.root.parent, "escape").exists())
    }

    @Test fun rejectsAbsolutePaths() {
        val files = contents + ("/escape" to byteArrayOf(1))
        val zip = archive(files)
        assertThrows(IllegalArgumentException::class.java) { install(CoreStore(folder.root), zip, manifest(zip, files)) }
    }

    @Test fun rejectsOversizedFile() {
        val zip = archive()
        val metadata = manifest(zip)
        val entries = metadata.entries + ("bin/node" to metadata.entries.getValue("bin/node").copy(size = 2))
        assertThrows(IllegalArgumentException::class.java) { install(CoreStore(folder.root), zip, metadata.copy(entries = entries)) }
    }

    @Test fun refusesInsufficientSpaceBeforeOpeningArchive() {
        val metadata = manifest(archive())
        assertThrows(IllegalArgumentException::class.java) {
            CoreStore(folder.root).install(metadata, { error("must not open") }, 0, { false }, { _, _ -> })
        }
    }

    @Test fun cancellationCleansStagingAndPreservesOldCoreAndUserFiles() {
        val store = CoreStore(folder.root)
        val zip = archive()
        val old = install(store, zip)
        val workspace = File(folder.root, "workspace").apply { mkdir() }
        File(workspace, "notes.md").writeText("keep")
        assertThrows(InterruptedException::class.java) {
            store.install(manifest(zip, version = "test-2"), { ByteArrayInputStream(zip) }, Long.MAX_VALUE, { true }, { _, _ -> })
        }
        assertNotNull(store.current(manifest(zip)))
        assertTrue(old.exists())
        assertEquals("keep", File(workspace, "notes.md").readText())
        assertFalse(File(folder.root, "test-2").exists())
        assertFalse(File(folder.root, ".install").exists())
    }

    @Test fun progressReachesTotalAfterVerification() {
        val zip = archive()
        val metadata = manifest(zip)
        val expectedTotal = metadata.totalSize
        var scans = 0
        val entries = object : Map<String, CoreStore.Entry> by metadata.entries {
            override val values: Collection<CoreStore.Entry>
                get() { scans++; return metadata.entries.values }
        }
        var done = 0L
        CoreStore(folder.root).install(metadata.copy(entries = entries), { ByteArrayInputStream(zip) }, Long.MAX_VALUE, { false }) { current, total ->
            assertTrue(current >= done)
            assertEquals(expectedTotal, total)
            done = current
        }
        assertEquals(metadata.totalSize, done)
        assertEquals("Installation must not rescan every entry for each progress update", 1, scans)
    }

    @Test fun rejectsInvalidManifestBeforeExtraction() {
        val json = JSONObject().put("format", 1).put("version", "../bad")
        assertThrows(IllegalArgumentException::class.java) { CoreStore.Manifest.parse(json.toString()) }
    }

    @Test fun acceptsOnlyAuthenticatedLoopbackLaunchLine() {
        assertEquals("http://127.0.0.1:3080/?token=sample", Engine.launchUrl("dsh web: http://127.0.0.1:3080/?token=sample"))
        assertEquals("http://127.0.0.1:3080/?token=sample&dshx-session=session-chosen", Engine.launchUrl("dsh web: http://127.0.0.1:3080/?token=sample", "session-chosen"))
        assertEquals("http://127.0.0.1:3080/?token=sample", Engine.launchUrl("dsh web: http://127.0.0.1:3080/?token=sample", "bad&token=other"))
        listOf(
            "dsh web: http://0.0.0.0:3080/?token=sample",
            "dsh web: http://127.0.0.1:0/?token=sample",
            "dsh web: http://127.0.0.1:65536/?token=sample",
            "dsh web: http://127.0.0.1:3080/",
            "fake dsh web: http://127.0.0.1:3080/?token=sample"
        ).forEach { assertNull(Engine.launchUrl(it)) }
    }

    @Test fun stripsLaunchTokensAndCommonApiKeysFromDiagnostics() {
        val log = Engine.redact("dsh web: http://127.0.0.1:3080/?token=secret&next=ok api_key=sk-123456789abcdef")
        assertFalse(log.contains("secret"))
        assertFalse(log.contains("123456789abcdef"))
        assertTrue(log.contains("next=ok"))
        assertFalse(Engine.redact("Authorization: Bearer super-secret-value").contains("super-secret-value"))
    }

    @Test fun refusesBrowserNavigationToOtherAuthoritiesOrSchemes() {
        val instance = "http://127.0.0.1:3080/?token=test"
        assertTrue(MainActivity.sameOrigin(instance, "http://127.0.0.1:3080/settings"))
        listOf("http://127.0.0.1:3081/", "https://example.com/", "file:///etc/passwd", "javascript:alert(1)", "http://user@127.0.0.1:3080/")
            .forEach { assertFalse(MainActivity.sameOrigin(instance, it)) }
    }

    @Test fun acceptsOnlySelectedReadableContentFiles() {
        val first = Uri.parse("content://documents/first.txt")
        val second = Uri.parse("content://documents/second.txt")
        val clip = ClipData.newRawUri("files", first).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(first))
        }
        val data = Intent().apply { clipData = clip }
        assertArrayEquals(arrayOf(first, second), MainActivity.selectedFiles(data, true) { it in listOf(first, second) })
        assertNull(MainActivity.selectedFiles(data, false) { true })
        assertArrayEquals(arrayOf(first), MainActivity.selectedFiles(Intent().setData(first), false) { it == first })
    }

    @Test fun rejectsPickerResponsesWithUnselectedOrLocalFiles() {
        listOf("file:///data/user/0/local.dshx.app/files/home/secret", "http://127.0.0.1/private", "content:/missing-authority")
            .forEach { value -> assertNull(MainActivity.selectedFiles(Intent().setData(Uri.parse(value)), false) { true }) }
        val selected = Uri.parse("content://documents/selected.txt")
        val unreadable = Uri.parse("content://documents/unselected.txt")
        val clip = ClipData.newRawUri("files", selected).apply { addItem(ClipData.Item(unreadable)) }
        assertNull(MainActivity.selectedFiles(Intent().apply { clipData = clip }, true) { it == selected })
        assertNull(MainActivity.selectedFiles(Intent().setData(selected), false) { false })
    }

    @Test fun cancelledAndNonFilePickerResponsesHaveNoAttachments() {
        assertNull(MainActivity.selectedFiles(null, true) { error("must not read") })
        assertNull(MainActivity.selectedFiles(Intent(), true) { error("must not read") })
        assertNull(MainActivity.selectedFiles(Intent().apply { clipData = ClipData.newPlainText("text", "not a file") }, true) { error("must not read") })
    }

    @Test fun defaultFullBelongsToLiveSessionsAndDoesNotStopWhenARootEnds() {
        val policy = ToolPolicy()
        policy.begin("first")
        policy.connected("first")
        assertEquals(ToolPolicy.FULL, policy.register("first", "root", "文件审核\n会话", null))
        assertEquals(ToolPolicy.FULL, policy.register("first", "child", "子会话", "root"))
        assertEquals(ToolPolicy.FULL, policy.register("first", "other", "另一会话", null))
        assertEquals("文件审核 会话", policy.state.sessions.first { it.id == "root" }.title)
        policy.end("first", "root")
        assertTrue(policy.state.connected)
        assertEquals(listOf("other"), policy.state.sessions.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { policy.mode("first", "child") }
        assertThrows(IllegalArgumentException::class.java) { policy.register("first", "root", "不能更换归属", "other") }
        assertEquals(ToolPolicy.FULL, policy.register("first", "root", "重新打开", null))
        policy.begin("second")
        policy.connected("second")
        policy.register("second", "root", "新引擎", null)
        policy.disconnect("first")
        assertEquals(ToolPolicy.FULL, policy.mode("second", "root"))
        assertThrows(IllegalArgumentException::class.java) { policy.mode("first", "root") }
    }

    @Test fun aColdHostReusesTheEnvironmentUnlessRecoveryWasRecorded() {
        val files = folder.newFolder("recovery")
        val recovery = EngineRecovery(files)
        assertFalse(recovery.needed)
        recovery.mark()
        assertTrue(EngineRecovery(files).needed)
        assertTrue(File(files, ".trust-pending").delete())
        assertTrue(recovery.needed)
        recovery.complete()
        assertFalse(recovery.needed)
        assertFalse(EngineRecovery(files).needed)
    }

    @Test fun nativePolicyRejectsUnknownParentsReparentingAndInvalidIds() {
        val policy = ToolPolicy()
        policy.begin("instance")
        assertThrows(IllegalArgumentException::class.java) { policy.register("instance", "root", "title", null) }
        policy.connected("instance")
        policy.register("instance", "root", "title", null)
        policy.register("instance", "child", "title", "root")
        listOf("", "../outside", "x".repeat(129)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { policy.register("instance", id, "title", null) }
        }
        assertThrows(IllegalArgumentException::class.java) { policy.register("instance", "unknown-child", "title", "missing") }
        assertThrows(IllegalArgumentException::class.java) { policy.register("instance", "child", "title", null) }
        policy.disconnect("instance")
        assertTrue(policy.state.sessions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { policy.mode("instance", "root") }
    }

    @Test fun policyBridgeAuthenticatesAndNeverAcceptsNetworkModeChanges() {
        val policy = ToolPolicy()
        policy.begin("instance")
        val lost = AtomicInteger()
        PolicyBridge(policy, "instance") { lost.incrementAndGet() }.use { bridge ->
            bridge.start()
            fun connect(token: String = bridge.token, version: Any = 1): Socket = Socket("127.0.0.1", bridge.port).apply {
                soTimeout = 3000
                getOutputStream().write((JSONObject().put("version", version).put("instance", "instance").put("token", token).toString() + "\n").toByteArray())
                getOutputStream().flush()
            }
            connect("wrong").use { assertEquals(-1, it.getInputStream().read()) }
            connect(version = "1").use { assertEquals(-1, it.getInputStream().read()) }
            assertFalse(policy.state.connected)
            connect().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(JSONObject(reader.readLine()).getBoolean("ok"))
                var id = 0
                fun query(method: String, extra: (JSONObject) -> Unit = {}): JSONObject {
                    val request = JSONObject().put("id", ++id).put("method", method).put("session", "root")
                    extra(request)
                    socket.getOutputStream().write((request.toString() + "\n").toByteArray())
                    socket.getOutputStream().flush()
                    return JSONObject(reader.readLine()).also { assertEquals(id, it.getInt("id")) }
                }
                assertTrue(query("register") { it.put("title", "当前会话").put("parent", JSONObject.NULL) }.getBoolean("ok"))
                assertFalse(query("set-mode") { it.put("mode", "danger-full-access") }.getBoolean("ok"))
                assertFalse(query("mode") { it.put("mode", "workspace-write") }.getBoolean("ok"))
                assertEquals(ToolPolicy.FULL, policy.mode("instance", "root"))
                assertEquals(ToolPolicy.FULL, query("mode").getJSONObject("state").getJSONArray("sessions").getJSONObject(0).getString("mode"))
                connect("intruder").use { assertEquals(-1, it.getInputStream().read()) }
                connect().use { assertEquals(-1, it.getInputStream().read()) }
                assertTrue(policy.state.connected)
                assertEquals(0, lost.get())
                assertEquals(ToolPolicy.FULL, query("mode").getJSONObject("state").getJSONArray("sessions").getJSONObject(0).getString("mode"))
                assertTrue(query("end").getBoolean("ok"))
                assertFalse(query("mode").getBoolean("ok"))
                bridge.close()
            }
        }
        assertEquals(0, lost.get())
        assertFalse(policy.state.connected)
    }

    @Test fun policyBridgeLosesAuthorityOnAuthenticatedDisconnect() {
        val policy = ToolPolicy()
        policy.begin("instance")
        val lost = CountDownLatch(1)
        PolicyBridge(policy, "instance") { lost.countDown() }.use { bridge ->
            bridge.start()
            val port = bridge.port
            Socket("127.0.0.1", bridge.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write((JSONObject().put("version", 1).put("instance", "instance").put("token", bridge.token).toString() + "\n").toByteArray())
                assertTrue(JSONObject(socket.getInputStream().bufferedReader().readLine()).getBoolean("ok"))
                policy.register("instance", "root", "title", null)
            }
            assertTrue(lost.await(3, TimeUnit.SECONDS))
            assertFalse(policy.state.connected)
            assertTrue(policy.state.sessions.isEmpty())
            // A TCP connect can race with listener teardown. It must never
            // receive an authenticated response, even if connect succeeds.
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 1000
                    socket.getOutputStream().write((JSONObject().put("version", 1).put("instance", "instance").put("token", bridge.token).toString() + "\n").toByteArray())
                    assertEquals(-1, socket.getInputStream().read())
                }
            } catch (error: java.io.IOException) {
                assertFalse("must reject rather than leave the reconnect waiting", error is java.net.SocketTimeoutException)
            }
        }
    }

    @Test fun policyBridgeLimitsTotalHandshakeTimeAndReleasesSlots() {
        val policy = ToolPolicy()
        policy.begin("instance")
        PolicyBridge(policy, "instance") { fail("strangers must not revoke the engine") }.use { bridge ->
            bridge.start()
            val sockets = List(4) { Socket("127.0.0.1", bridge.port).apply { soTimeout = 2500 } }
            try {
                sockets.forEach { it.getOutputStream().write(' '.code) }
                Thread.sleep(1800)
                sockets.forEach { it.getOutputStream().write(' '.code) }
                sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
                assertFalse(policy.state.connected)
                Socket("127.0.0.1", bridge.port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write((JSONObject().put("version", 1).put("instance", "instance").put("token", bridge.token).toString() + "\n").toByteArray())
                    assertTrue(JSONObject(socket.getInputStream().bufferedReader().readLine()).getBoolean("ok"))
                    bridge.close()
                }
            } finally { sockets.forEach { it.close() } }
        }
    }

    private class PolicyConnection(bridge: PolicyBridge) : java.io.Closeable {
        private val socket = Socket().apply {
            connect(java.net.InetSocketAddress("127.0.0.1", bridge.port), 3000)
            soTimeout = 3000
        }
        private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

        init {
            write(JSONObject().put("version", 1).put("instance", bridge.instance).put("token", bridge.token))
            assertTrue(read(0).getBoolean("ok"))
        }

        private fun write(value: JSONObject) {
            socket.getOutputStream().apply {
                write((value.toString() + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
        }

        fun send(id: Int, method: String, session: String = "root", extra: JSONObject.() -> Unit = {}) {
            write(JSONObject().put("id", id).put("method", method).put("session", session).apply(extra))
        }

        fun read(id: Int): JSONObject = JSONObject(requireNotNull(reader.readLine()) { "桥接连接提前关闭" }).also {
            assertEquals("回执必须对应当前请求", id, it.getInt("id"))
        }

        fun query(id: Int, method: String, session: String = "root", extra: JSONObject.() -> Unit = {}): JSONObject {
            send(id, method, session, extra)
            return read(id)
        }

        override fun close() = socket.close()
    }

    private class PendingPhone : java.io.Closeable {
        private val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        lateinit var action: String
            private set
        lateinit var args: JSONObject
            private set
        lateinit var session: String
            private set
        private lateinit var valid: () -> Boolean

        fun execute(action: String, args: JSONObject, session: String, valid: () -> Boolean): JSONObject {
            calls.incrementAndGet()
            this.action = action
            this.args = args
            this.session = session
            this.valid = valid
            started.countDown()
            try {
                check(release.await(10, TimeUnit.SECONDS)) { "测试未及时释放手机处理函数" }
                return JSONObject().put("accepted", true).put("valid", valid())
            } finally { finished.countDown() }
        }

        fun isValid(): Boolean {
            assertTrue("手机处理函数未在期限内开始", started.await(3, TimeUnit.SECONDS))
            return valid()
        }

        override fun close() {
            release.countDown()
            if (calls.get() > 0) assertTrue("手机处理函数未在期限内退出", finished.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun policyBridgeRejectsUnknownSessionsAndInvalidPhoneRequestsBeforeExecution() {
        val policy = ToolPolicy().apply { begin("instance") }
        val calls = AtomicInteger()
        val actions = AtomicInteger()
        PolicyBridge(policy, "instance", phone = { action, _, _, _ ->
            calls.incrementAndGet()
            // 动作名称由处理函数校验；只有通过校验才算执行手机操作。
            require(action == "observe") { "不支持的手机操作" }
            actions.incrementAndGet()
            JSONObject().put("accepted", true)
        }) {}.use { bridge ->
            bridge.start()
            PolicyConnection(bridge).use { client ->
                val unknown = client.query(1, "phone", "missing") {
                    put("action", "observe").put("args", JSONObject())
                }
                assertFalse(unknown.getBoolean("ok"))
                assertFalse(unknown.has("result"))
                assertEquals(0, calls.get())
                assertTrue(client.query(2, "register") { put("title", "手机会话") }.getBoolean("ok"))
                val invalid: List<JSONObject.() -> Unit> = listOf(
                    { put("args", JSONObject()) },
                    { put("action", "observe").put("args", "不是对象") },
                    { put("action", "observe").put("args", JSONObject()).put("extra", true) },
                )
                invalid.forEachIndexed { index, fields ->
                    val response = client.query(index + 3, "phone", extra = fields)
                    assertFalse(response.getBoolean("ok"))
                    assertFalse(response.has("result"))
                    assertEquals(0, calls.get())
                }
                val unsupported = client.query(6, "phone") {
                    put("action", "unsupported").put("args", JSONObject())
                }
                assertFalse(unsupported.getBoolean("ok"))
                assertFalse(unsupported.has("result"))
                assertEquals(1, calls.get())
                assertEquals(0, actions.get())
                assertTrue(client.query(7, "mode").getBoolean("ok"))
                bridge.close()
            }
        }
    }

    @Test fun policyBridgeReturnsPhoneResultWithoutBlockingModeAndRejectsASecondPhoneCall() {
        val policy = ToolPolicy().apply { begin("instance") }
        PendingPhone().use { phone ->
            PolicyBridge(policy, "instance", phone = phone::execute) {}.use { bridge ->
                bridge.start()
                PolicyConnection(bridge).use { client ->
                    assertTrue(client.query(1, "register") { put("title", "手机会话") }.getBoolean("ok"))
                    // 转义后的输入超过旧的 8 KiB 限制，仍须原样传给处理函数。
                    val text = "\u0001".repeat(1500) + "测试中文"
                    val args = JSONObject().put("snapshot", "观察-1").put("node", "控件-2").put("text", text)
                    assertTrue(args.toString().toByteArray(Charsets.UTF_8).size > 8 * 1024)
                    assertTrue(args.toString().toByteArray(Charsets.UTF_8).size < 32 * 1024)
                    client.send(2, "phone") { put("action", "input").put("args", args) }
                    assertTrue(phone.isValid())
                    assertEquals("input", phone.action)
                    assertEquals("root", phone.session)
                    assertEquals(setOf("snapshot", "node", "text"), phone.args.keys().asSequence().toSet())
                    args.keys().forEach { key -> assertEquals(args.get(key), phone.args.get(key)) }
                    // 处理函数仍被信号挡住，mode 必须先于 phone 回执返回。
                    val mode = client.query(3, "mode")
                    assertTrue(mode.getBoolean("ok"))
                    assertEquals(ToolPolicy.FULL, mode.getJSONObject("state").getJSONArray("sessions")
                        .getJSONObject(0).getString("mode"))
                    val second = client.query(4, "phone") { put("action", "home").put("args", JSONObject()) }
                    assertFalse(second.getBoolean("ok"))
                    assertFalse(second.has("result"))
                    assertEquals(1, phone.calls.get())
                    assertTrue(phone.isValid())
                    phone.release.countDown()
                    val response = client.read(2)
                    assertTrue(response.getBoolean("ok"))
                    val result = response.getJSONObject("result")
                    assertEquals(setOf("accepted", "valid"), result.keys().asSequence().toSet())
                    assertTrue(result.getBoolean("accepted"))
                    assertTrue(result.getBoolean("valid"))
                    assertTrue(client.query(5, "mode").getBoolean("ok"))
                    bridge.close()
                }
            }
        }
    }

    @Test fun policyBridgeCancelsOnlyTheMatchingPhoneCallAndKeepsTheConnection() {
        val policy = ToolPolicy().apply { begin("instance") }
        PendingPhone().use { phone ->
            PolicyBridge(policy, "instance", phone = phone::execute) {}.use { bridge ->
                bridge.start()
                PolicyConnection(bridge).use { client ->
                    assertTrue(client.query(1, "register") { put("title", "手机会话") }.getBoolean("ok"))
                    client.send(2, "phone") { put("action", "observe").put("args", JSONObject()) }
                    assertTrue(phone.isValid())
                    assertTrue(client.query(3, "phone-cancel") { put("call", 99) }.getBoolean("ok"))
                    assertTrue(phone.isValid())
                    assertTrue(client.query(4, "phone-cancel", "other") { put("call", 2) }.getBoolean("ok"))
                    assertTrue(phone.isValid())
                    assertTrue(client.query(5, "phone-cancel") { put("call", 2) }.getBoolean("ok"))
                    assertFalse(phone.isValid())
                    assertTrue(client.query(6, "mode").getBoolean("ok"))
                    // 即使替身返回 accepted，桥接层也必须拒绝已取消请求的成功结果。
                    phone.release.countDown()
                    val response = client.read(2)
                    assertFalse(response.getBoolean("ok"))
                    assertFalse(response.has("result"))
                    assertTrue(response.getString("error").isNotBlank())
                    assertEquals(1, phone.calls.get())
                    assertTrue(client.query(7, "mode").getBoolean("ok"))
                    bridge.close()
                }
            }
        }
    }

    @Test fun policyBridgeInvalidatesPhoneCallsWhenTheSessionEndsOrTheBridgeCloses() {
        listOf("end", "close").forEach { ending ->
            val policy = ToolPolicy().apply { begin("instance") }
            PendingPhone().use { phone ->
                PolicyBridge(policy, "instance", phone = { action, args, session, valid ->
                    val result = phone.execute(action, args, session, valid)
                    check(valid()) { "会话或桥接已失效" }
                    result
                }) {}.use { bridge ->
                    bridge.start()
                    PolicyConnection(bridge).use { client ->
                        assertTrue(client.query(1, "register") { put("title", "手机会话") }.getBoolean("ok"))
                        client.send(2, "phone") { put("action", "observe").put("args", JSONObject()) }
                        assertTrue(phone.isValid())
                        if (ending == "end") {
                            assertTrue(client.query(3, "end").getBoolean("ok"))
                            assertTrue(policy.state.connected)
                            assertTrue(policy.state.sessions.isEmpty())
                            assertFalse(phone.isValid())
                            phone.release.countDown()
                            val response = client.read(2)
                            assertFalse(response.getBoolean("ok"))
                            assertFalse(response.has("result"))
                            assertFalse(client.query(4, "mode").getBoolean("ok"))
                            assertTrue(client.query(5, "register") { put("title", "重新登记") }.getBoolean("ok"))
                            assertTrue(client.query(6, "mode").getBoolean("ok"))
                        } else {
                            bridge.close()
                            assertFalse(phone.isValid())
                            assertFalse(policy.state.connected)
                            assertTrue(policy.state.sessions.isEmpty())
                        }
                        assertEquals(1, phone.calls.get())
                        bridge.close()
                    }
                }
            }
        }
    }

    @Test fun policyBridgeNeverRevivesAnOldObservationWhenTheSameSessionRegistersAgain() {
        val policy = ToolPolicy().apply { begin("instance") }
        val saved = AtomicReference<() -> Boolean>()
        PolicyBridge(policy, "instance", phone = { _, _, _, valid ->
            saved.set(valid)
            JSONObject().put("snapshot", "观察结果")
        }) {}.use { bridge ->
            bridge.start()
            PolicyConnection(bridge).use { client ->
                assertTrue(client.query(1, "register") { put("title", "手机会话") }.getBoolean("ok"))
                val owner = policy.state.sessions.single()
                val observation = client.query(2, "phone") { put("action", "observe").put("args", JSONObject()) }
                assertTrue(observation.getBoolean("ok"))
                assertEquals("观察结果", observation.getJSONObject("result").getString("snapshot"))
                val oldValid = requireNotNull(saved.get())
                assertTrue(oldValid())
                assertTrue(client.query(3, "end").getBoolean("ok"))
                assertFalse(oldValid())
                assertTrue(client.query(4, "register") { put("title", "手机会话") }.getBoolean("ok"))
                // 字段完全相同，但本次登记必须属于一个新对象，旧观察不能随 ID 复活。
                assertEquals(owner, policy.state.sessions.single())
                assertNotSame(owner, policy.state.sessions.single())
                assertFalse(oldValid())
                assertTrue(client.query(5, "mode").getBoolean("ok"))
                assertTrue(client.query(6, "phone") {
                    put("action", "observe").put("args", JSONObject())
                }.getBoolean("ok"))
                val newValid = requireNotNull(saved.get())
                assertTrue(newValid())
                assertFalse(oldValid())
                bridge.close()
                assertFalse(newValid())
                assertFalse(oldValid())
            }
        }
    }

    private fun phoneFailure(code: String, result: JSONObject) {
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals(result.toString(), code, result.getString("code"))
        assertEquals("not_started", result.getString("execution_state"))
    }

    private fun phoneService(): ServiceController<PhoneService> {
        val controller = Robolectric.buildService(PhoneService::class.java).create()
        ReflectionHelpers.callInstanceMethod<Void>(controller.get(), "onServiceConnected")
        assertSame(controller.get(), PhoneService.current)
        return controller
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun phoneWindowWaitsForPictureInPictureAndStaysUntilItsSessionsFinish() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val controller = phoneService()
        val window = PhoneWindow(context) { fail("display must not stop the phone") }
        val calls = AtomicInteger()
        try {
            window.show("root", "observe", { true }) { calls.incrementAndGet() }
            assertEquals(0, calls.get())
            assertEquals(PhoneWindowActivity::class.java.name, shadowOf(context).nextStartedActivity.component!!.className)
            assertSame(window, PhoneWindow.current)
            window.entered()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            assertEquals(1, calls.get())
            window.result(PhoneControl.success())
            assertTrue(window.detail.contains("等待助手下一步"))
            window.show("child", "input", { true }) { calls.incrementAndGet() }
            assertEquals(2, calls.get())
            assertEquals(2, window.count)
            assertEquals("正在输入文字", window.title)
            assertNull("同一任务不能反复打开页面", shadowOf(context).nextStartedActivity)
            window.finish("unrelated")
            window.finish("root")
            assertSame(window, PhoneWindow.current)
            window.finish("child")
            assertNull(PhoneWindow.current)
        } finally { window.close(); controller.destroy() }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun phoneWindowStopDisablesPhoneAndDiscardsWorkBeforePictureInPictureIsReady() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val controller = phoneService()
        val control = PhoneControl(context)
        try {
            control.setEnabled(true)
            control.window.show("root", "open", { true }) { fail("closed window dispatched pending work") }
            val oldWindow = control.window.id
            control.window.entered()
            control.window.stop()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertFalse(control.enabled())
            assertNull(PhoneWindow.current)
            control.setEnabled(true)
            control.window.show("root", "observe", { true }) { fail("another control instance left pending work") }
            assertNotEquals(oldWindow, control.window.id)
            PhoneControl(context).setEnabled(false)
            assertNull(PhoneWindow.current)
            control.setEnabled(true)
            control.window.show("root", "observe", { true }) { fail("disconnected service dispatched pending work") }
            controller.destroy()
            assertNull(PhoneWindow.current)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        } finally { control.setEnabled(false); if (PhoneService.current != null) controller.destroy() }
    }

    @Test fun policyBridgeOnlyFinishesPhoneWindowsForAuthenticatedLiveSessions() {
        val policy = ToolPolicy().apply { begin("instance") }
        val finished = mutableListOf<String>()
        PolicyBridge(policy, "instance", phoneFinished = { synchronized(finished) { finished.add(it) } }) {}.use { bridge ->
            bridge.start()
            PolicyConnection(bridge).use { client ->
                assertTrue(client.query(1, "register") { put("title", "手机操作") }.getBoolean("ok"))
                assertFalse(client.query(2, "phone-finish", "missing").getBoolean("ok"))
                assertFalse(client.query(3, "phone-finish") { put("extra", true) }.getBoolean("ok"))
                assertTrue(client.query(4, "phone-finish").getBoolean("ok"))
                assertEquals(listOf("root"), synchronized(finished) { finished.toList() })
                assertTrue(client.query(5, "mode").getBoolean("ok"))
                assertEquals(ToolPolicy.FULL, policy.mode("instance", "root"))
            }
        }
    }

    @Test fun phoneControlDefaultsToDisabledAndPersistsItsSwitchIndependentlyOfFull() {
        val context = RuntimeEnvironment.getApplication()
        val control = PhoneControl(context)
        val policy = ToolPolicy().apply { begin("instance"); connected("instance") }
        assertEquals(ToolPolicy.FULL, policy.register("instance", "root", "手机会话", null))
        assertFalse(control.enabled())
        assertFalse(control.status().getBoolean("ready"))
        control.setEnabled(true)
        assertTrue(PhoneControl(context).enabled())
        policy.end("instance", "root")
        assertTrue(PhoneControl(context).enabled())
        assertEquals(ToolPolicy.FULL, policy.register("instance", "root", "手机会话", null))
        PhoneControl(context).setEnabled(false)
        assertFalse(control.enabled())
        assertFalse(PhoneControl(context).enabled())
        assertEquals(ToolPolicy.FULL, policy.mode("instance", "root"))
    }

    @Test @Config(sdk = [26, 34])
    fun phoneControlStatusReportsScreenshotSupportAndReadinessForTheAndroidVersion() {
        val context = RuntimeEnvironment.getApplication()
        val control = PhoneControl(context)
        val initial = control.execute("status", JSONObject(), "root") { true }
        assertTrue(initial.getBoolean("ok"))
        assertEquals(Build.VERSION.SDK_INT == 34, initial.getBoolean("screenshotSupported"))
        assertEquals(Build.VERSION.SDK_INT >= 30, initial.getBoolean("coordinateSupported"))
        listOf("enabled", "accessibility", "ready", "screenshotReady").forEach { key ->
            assertFalse(key, initial.getBoolean(key))
        }
        shadowOf(context.getSystemService(KeyguardManager::class.java)).apply {
            setKeyguardLocked(false)
            setIsDeviceLocked(false)
        }
        shadowOf(context.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        val controller = phoneService()
        try {
            control.setEnabled(true)
            val ready = control.execute("status", JSONObject(), "root") { true }
            assertTrue(ready.getBoolean("ok"))
            assertTrue(ready.getBoolean("enabled"))
            assertTrue(ready.getBoolean("accessibility"))
            assertTrue(ready.getBoolean("ready"))
            assertFalse(ready.getBoolean("locked"))
            assertEquals(Build.VERSION.SDK_INT == 34, ready.getBoolean("screenshotReady"))
            control.setEnabled(false)
            assertFalse(control.status().getBoolean("ready"))
            assertFalse(control.status().getBoolean("screenshotReady"))
        } finally { controller.destroy() }
    }

    @Test fun phoneControlRejectsUnknownMethodsExtraFieldsAndMissingTextButAllowsAnEmptyString() {
        val control = PhoneControl(RuntimeEnvironment.getApplication())
        val checks = AtomicInteger()
        phoneFailure("unknown_method", control.execute("unsupported", JSONObject(), "root") {
            checks.incrementAndGet()
            true
        })
        assertEquals(0, checks.get())
        phoneFailure("invalid_args", control.execute("status", JSONObject().put("extra", true), "root") { true })
        phoneFailure("invalid_args", control.execute("observe", JSONObject().put("extra", true), "root") { true })
        val input = JSONObject().put("snapshot", "观察-1").put("node", "控件-2")
        phoneFailure("invalid_args", control.execute("input", input, "root") { true })
        listOf<Any>(JSONObject.NULL, 0, false).forEach { text ->
            phoneFailure("invalid_args", control.execute("input", JSONObject(input.toString()).put("text", text), "root") { true })
        }
        // 空串通过参数检查，随后才因当前是主线程而被拒绝；缺少 text 则在参数层拒绝。
        phoneFailure("wrong_thread", control.execute("input", input.put("text", ""), "root") { true })
        phoneFailure("invalid_args", control.execute("input", input.put("extra", true), "root") { true })
    }

    @Test fun phoneControlRejectsInvalidSessionsWithoutDispatchingAnAction() {
        val control = PhoneControl(RuntimeEnvironment.getApplication())
        val controller = phoneService()
        try {
            control.setEnabled(true)
            phoneFailure("cancelled", control.execute("home", JSONObject(), "root") { false })
            phoneFailure("cancelled", control.execute("home", JSONObject(), "root") { error("权限检查失败") })
            phoneFailure("cancelled", control.execute("home", JSONObject(), "") { true })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun phoneControlRefusesDisabledOfflineLockedAndScreenOffOperations() {
        val context = RuntimeEnvironment.getApplication()
        val control = PhoneControl(context)
        val worker = Executors.newSingleThreadExecutor()
        fun execute(): JSONObject = worker.submit<JSONObject> {
            control.execute("home", JSONObject(), "root") { true }
        }.get(3, TimeUnit.SECONDS)
        try {
            phoneFailure("disabled", execute())
            control.setEnabled(true)
            phoneFailure("accessibility_offline", execute())
            val controller = phoneService()
            try {
                val lock = shadowOf(context.getSystemService(KeyguardManager::class.java))
                val power = shadowOf(context.getSystemService(PowerManager::class.java))
                power.turnScreenOn(true)
                lock.setKeyguardLocked(true)
                lock.setIsDeviceLocked(false)
                phoneFailure("locked", execute())
                lock.setKeyguardLocked(false)
                lock.setIsDeviceLocked(true)
                phoneFailure("locked", execute())
                lock.setIsDeviceLocked(false)
                power.turnScreenOn(false)
                phoneFailure("screen_off", execute())
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
            } finally { controller.destroy() }
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun phoneControlDisablingTheSwitchCancelsQueuedWorkWithoutDispatchingIt() {
        val context = RuntimeEnvironment.getApplication()
        val control = PhoneControl(context)
        val controller = phoneService()
        val worker = Executors.newSingleThreadExecutor()
        val queued = CountDownLatch(1)
        val checks = AtomicInteger()
        try {
            shadowOf(context.getSystemService(KeyguardManager::class.java)).apply {
                setKeyguardLocked(false)
                setIsDeviceLocked(false)
            }
            shadowOf(context.getSystemService(PowerManager::class.java)).turnScreenOn(true)
            control.setEnabled(true)
            val result = worker.submit<JSONObject> {
                control.execute("home", JSONObject(), "root") {
                    // 首次校验在入队前；第二次校验发生在主线程任务入队后的等待阶段。
                    if (checks.incrementAndGet() >= 2) queued.countDown()
                    true
                }
            }
            assertTrue("手机请求未在期限内入队", queued.await(3, TimeUnit.SECONDS))
            PhoneControl(context).setEnabled(false)
            phoneFailure("disabled", result.get(3, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
            assertFalse(control.enabled())
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            controller.destroy()
        }
    }

    @Implements(AccessibilityService::class)
    @Suppress("DEPRECATION")
    class PhoneAccess : ShadowAccessibilityService() {
        var root: AccessibilityNodeInfo? = null

        @Implementation
        protected fun getRootInActiveWindow(): AccessibilityNodeInfo? =
            root?.let(AccessibilityNodeInfo::obtain)

        @Implementation
        protected override fun getWindows(): List<AccessibilityWindowInfo> =
            // 生产代码会回收每次查询的窗口；保留夹具原件供下一次查询使用。
            super.getWindows().map(AccessibilityWindowInfo::obtain)
    }

    @Suppress("DEPRECATION")
    private fun phoneNode(window: AccessibilityWindowInfo, text: String): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = "local.dshx.target"
            className = "android.widget.TextView"
            this.text = text
            isVisibleToUser = true
            isEnabled = true
            setBoundsInScreen(Rect(10, 10, 90, 30))
            shadowOf(this).setAccessibilityWindowInfo(window)
        }

    @Suppress("DEPRECATION")
    private fun phoneRoot(service: PhoneService): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(1)
            setDisplayId(Display.DEFAULT_DISPLAY)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setLayer(0)
            setActive(true)
            setBoundsInScreen(Rect(0, 0, 100, 100))
        }
        val root = phoneNode(window, "")
        shadowOf(window).setRoot(root)
        (shadowOf(service) as PhoneAccess).apply {
            this.root = root
            setWindows(listOf(window))
        }
        return root
    }

    @Suppress("DEPRECATION")
    private fun phoneCover(service: PhoneService, root: AccessibilityNodeInfo): AccessibilityWindowInfo {
        val cover = AccessibilityWindowInfo.obtain()
        shadowOf(cover).apply {
            setId(2)
            setDisplayId(Display.DEFAULT_DISPLAY)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setLayer(1)
            setBoundsInScreen(Rect(50, 50, 100, 100))
        }
        (shadowOf(service) as PhoneAccess).setWindows(listOf(root.window, cover))
        return cover
    }

    private fun phoneTarget(service: PhoneService, id: String): AccessibilityNodeInfo {
        // Robolectric 的 refresh 只返回预设布尔值，不会把原节点变化同步到服务保留的副本。
        val frame = ReflectionHelpers.getField<Any>(service, "frame")
        val targets = ReflectionHelpers.getField<Map<String, Any>>(frame, "targets")
        return ReflectionHelpers.getField(targets.getValue(id), "node")
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlPartialCoverKeepsOnlyNativeScrollActions() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            phoneCover(service, root)
            val node = phoneNode(root.window, "").apply {
                setBoundsInScreen(Rect(10, 10, 90, 90))
                isClickable = true
                isEditable = true
                isScrollable = true
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
            shadowOf(root).addChild(node)
            shadowOf(node).setRefreshReturnValue(true)
            shadowOf(node).setOnPerformActionListener { action, _ ->
                action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            val dispatched = AtomicInteger()
            fun arguments(): JSONObject {
                val snapshot = service.observe("root", { true }, {})
                val nodes = snapshot.getJSONArray("nodes")
                assertEquals(1, nodes.length())
                val row = nodes.getJSONObject(0)
                val actions = row.getJSONArray("actions")
                assertEquals(listOf("forward", "backward"), (0 until actions.length()).map(actions::getString))
                return JSONObject().put("snapshot", snapshot.getString("snapshot")).put("node", row.getString("id"))
            }
            for (method in listOf("click", "input")) {
                val args = arguments().put("text", "blocked")
                assertEquals("stale_snapshot", assertThrows(PhoneFailure::class.java) {
                    service.act(method, args, "root", {}, { dispatched.incrementAndGet() })
                }.code)
            }
            assertEquals(0, dispatched.get())
            assertTrue(shadowOf(node).performedActions.isEmpty())
            for (direction in listOf("forward", "backward")) {
                val result = service.act("scroll", arguments().put("direction", direction), "root", {}, { dispatched.incrementAndGet() })
                assertTrue(result.getBoolean("ok"))
                assertTrue(result.getBoolean("accepted"))
            }
            assertEquals(2, dispatched.get())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
                shadowOf(node).performedActions)
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlScrollRequiresVisibleEnabledAndUnprotectedArea() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            val cover = phoneCover(service, root)
            val node = phoneNode(root.window, "").apply {
                setBoundsInScreen(Rect(10, 10, 90, 90))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
            shadowOf(root).addChild(node)
            shadowOf(node).setRefreshReturnValue(true)
            val changes = listOf<Pair<String, (AccessibilityNodeInfo) -> Unit>>(
                "fully covered" to { _ -> shadowOf(cover).setBoundsInScreen(Rect(0, 0, 100, 100)) },
                "outside window" to { it.setBoundsInScreen(Rect(110, 110, 150, 150)) },
                "disabled" to { it.isEnabled = false },
                "hidden" to { it.isVisibleToUser = false },
                "password" to { it.isPassword = true },
                "sensitive" to { it.isAccessibilityDataSensitive = true },
                "password ancestor" to { _ -> root.isPassword = true },
                "sensitive ancestor" to { _ -> root.isAccessibilityDataSensitive = true },
            )
            for ((name, change) in changes) {
                shadowOf(cover).setBoundsInScreen(Rect(50, 50, 100, 100))
                node.setBoundsInScreen(Rect(10, 10, 90, 90))
                node.isEnabled = true
                node.isVisibleToUser = true
                node.isPassword = false
                node.isAccessibilityDataSensitive = false
                root.isPassword = false
                root.isAccessibilityDataSensitive = false
                val snapshot = service.observe("root", { true }, {})
                assertEquals(name, 1, snapshot.getJSONArray("nodes").length())
                val args = JSONObject().put("snapshot", snapshot.getString("snapshot"))
                    .put("node", snapshot.getJSONArray("nodes").getJSONObject(0).getString("id")).put("direction", "forward")
                change(node)
                change(phoneTarget(service, args.getString("node")))
                assertThrows(name, PhoneFailure::class.java) {
                    service.act("scroll", args, "root", {}, { fail("$name dispatched") })
                }
                assertEquals(name, 0, service.observe("root", { true }, {}).getJSONArray("nodes").length())
            }
            assertTrue(shadowOf(node).performedActions.isEmpty())
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlScrollRechecksVisibilityBeforeDispatch() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            phoneCover(service, root)
            val node = phoneNode(root.window, "").apply {
                setBoundsInScreen(Rect(10, 10, 90, 90))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
            shadowOf(root).addChild(node)
            shadowOf(node).setRefreshReturnValue(true)
            val snapshot = service.observe("root", { true }, {})
            val args = JSONObject().put("snapshot", snapshot.getString("snapshot"))
                .put("node", snapshot.getJSONArray("nodes").getJSONObject(0).getString("id")).put("direction", "forward")
            val target = phoneTarget(service, args.getString("node"))
            val checks = AtomicInteger()
            assertEquals("stale_snapshot", assertThrows(PhoneFailure::class.java) {
                service.act("scroll", args, "root", {
                    if (checks.incrementAndGet() == 2) target.isVisibleToUser = false
                }, { fail("hidden scroll dispatched") })
            }.code)
            assertEquals(2, checks.get())
            assertTrue(shadowOf(node).performedActions.isEmpty())
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlCoordinatesStillRejectPartialCover() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            phoneCover(service, root)
            for (method in listOf("tap", "swipe")) {
                val args = phoneImage(service, root).put("x", if (method == "tap") 60 else 20)
                    .put("y", if (method == "tap") 60 else 20).put("endX", 60).put("endY", 60).put("ms", 100)
                assertEquals("obscured", assertThrows(PhoneFailure::class.java) {
                    service.gesture(method, args, "root", {}, { fail("covered $method dispatched") }, { fail("covered $method completed") })
                }.code)
            }
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationReachesVisibleRowsAfterHiddenRowsWithCostlyChecks() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            repeat(40) { index ->
                shadowOf(root).addChild(phoneNode(root.window, "不可见行$index").apply {
                    setBoundsInScreen(Rect(10, 10, 90, -400 + index * 20))
                    isVisibleToUser = false
                })
            }
            val last = phoneNode(root.window, "第24项").apply {
                isClickable = true
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            shadowOf(root).addChild(last)
            val checks = AtomicInteger()
            val result = service.observe("root", { true }) {
                checks.incrementAndGet()
                // 只推进虚拟时钟，模拟锁屏/亮屏系统查询成本，不实际等待。
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(40))
            }
            assertFalse(result.getBoolean("truncated"))
            val nodes = result.getJSONArray("nodes")
            assertEquals(1, nodes.length())
            assertEquals("第24项", nodes.getJSONObject(0).getString("text"))
            assertEquals("click", nodes.getJSONObject(0).getJSONArray("actions").getString(0))
            assertEquals("入口、16/32节点处、返回前各检查一次", 4, checks.get())
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationCancelsDuringTraversalWithoutKeepingASnapshot() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            repeat(40) { shadowOf(root).addChild(phoneNode(root.window, "行$it")) }
            val checks = AtomicInteger()
            assertEquals("cancelled", assertThrows(PhoneFailure::class.java) {
                service.observe("root", { true }) {
                    // 入口通过，遍历到第16个节点时发现取消。
                    if (checks.incrementAndGet() == 2) throw PhoneFailure("cancelled", "已取消")
                }
            }.code)
            assertEquals(2, checks.get())
            assertNull(ReflectionHelpers.getField<Any?>(service, "frame"))
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationChecksStateBeforeReturningEvenBelowTheCheckInterval() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            shadowOf(root).addChild(phoneNode(root.window, "可见行"))
            for (code in listOf("cancelled", "disabled", "locked", "screen_off", "timeout")) {
                val checks = AtomicInteger()
                assertEquals(code, assertThrows(PhoneFailure::class.java) {
                    service.observe("root", { true }) {
                        if (checks.incrementAndGet() == 2) throw PhoneFailure(code, "最终检查拒绝")
                    }
                }.code)
                assertEquals(2, checks.get())
                assertNull(ReflectionHelpers.getField<Any?>(service, "frame"))
            }
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationRechecksWindowChangesAfterTheFinalStateCheck() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            shadowOf(root).addChild(phoneNode(root.window, "可见行"))
            val checks = AtomicInteger()
            assertEquals("stale_snapshot", assertThrows(PhoneFailure::class.java) {
                service.observe("root", { true }) {
                    if (checks.incrementAndGet() == 2) {
                        service.onAccessibilityEvent(android.view.accessibility.AccessibilityEvent.obtain(
                            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
                    }
                }
            }.code)
            assertEquals(2, checks.get())
            assertNull(ReflectionHelpers.getField<Any?>(service, "frame"))
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationKeepsReadOnlyTextAndHidesPasswordInvisibleAndSensitiveNodes() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            val window = root.window
            val text = phoneNode(window, "只读中文状态")
            val password = phoneNode(window, "密码不可泄露").apply { isPassword = true; contentDescription = "密码提示不可泄露" }
            val hidden = phoneNode(window, "隐藏内容不可泄露").apply { isVisibleToUser = false }
            val sensitive = phoneNode(window, "敏感内容不可泄露").apply { isAccessibilityDataSensitive = true }
            listOf(text, password, hidden, sensitive).forEach { shadowOf(root).addChild(it) }
            val result = service.observe("root", { true }, {})
            assertTrue(result.getBoolean("ok"))
            assertTrue(result.getString("snapshot").isNotBlank())
            val nodes = result.getJSONArray("nodes")
            assertEquals(1, nodes.length())
            val row = nodes.getJSONObject(0)
            assertEquals("只读中文状态", row.getString("text"))
            assertFalse(row.getBoolean("editable"))
            assertEquals(0, row.getJSONArray("actions").length())
            assertFalse(result.toString().contains("不可泄露"))
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationReportsCheckedSelectedAndStateOnlyNodes() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            val toggle = phoneNode(root.window, "同步").apply { isCheckable = true; isChecked = true; isSelected = true; stateDescription = "已开启" }
            val state = phoneNode(root.window, "").apply { stateDescription = "正在连接" }
            listOf(toggle, state).forEach { shadowOf(root).addChild(it) }
            val nodes = service.observe("root", { true }, {}).getJSONArray("nodes")
            assertEquals(2, nodes.length())
            val row = nodes.getJSONObject(0)
            assertTrue(row.getBoolean("checkable"))
            assertTrue(row.getBoolean("checked"))
            assertTrue(row.getBoolean("selected"))
            assertEquals("已开启", row.getString("stateDescription"))
            assertEquals("正在连接", nodes.getJSONObject(1).getString("stateDescription"))
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlObservationUsesFullDisplayBoundsForBottomControls() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            val window = root.window
            val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val real = android.graphics.Point().also(display::getRealSize)
            val metrics = service.resources.displayMetrics
            val previousHeight = metrics.heightPixels
            try {
                metrics.heightPixels = real.y / 2
                shadowOf(window).setBoundsInScreen(Rect(0, 0, real.x, real.y))
                val node = phoneNode(window, "底部按钮").apply {
                    setBoundsInScreen(Rect(10, real.y - 40, minOf(90, real.x), real.y - 10))
                    isClickable = true
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                }
                shadowOf(root).addChild(node)
                val nodes = service.observe("root", { true }, {}).getJSONArray("nodes")
                assertEquals(1, nodes.length())
                assertEquals("底部按钮", nodes.getJSONObject(0).getString("text"))
                assertEquals("click", nodes.getJSONObject(0).getJSONArray("actions").getString(0))
            } finally { metrics.heightPixels = previousHeight }
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlEmptyInputReachesTheEditableNodeAndConsumesItsSnapshot() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            val node = phoneNode(root.window, "待清空文本").apply {
                isEditable = true
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            }
            shadowOf(root).addChild(node)
            shadowOf(node).setRefreshReturnValue(true)
            shadowOf(node).setOnPerformActionListener { action, _ -> action == AccessibilityNodeInfo.ACTION_SET_TEXT }
            val snapshot = service.observe("root", { true }, {})
            val args = JSONObject().put("snapshot", snapshot.getString("snapshot"))
                .put("node", snapshot.getJSONArray("nodes").getJSONObject(0).getString("id")).put("text", "")
            val dispatched = AtomicInteger()
            val result = service.act("input", args, "root", {}, { dispatched.incrementAndGet() })
            assertTrue(result.getBoolean("ok"))
            assertTrue(result.getBoolean("accepted"))
            assertEquals(1, dispatched.get())
            val actions = shadowOf(node).performedActionsWithArgs
            assertEquals(1, actions.size)
            assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, actions.single().first)
            assertEquals("", actions.single().second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            val stale = assertThrows(PhoneFailure::class.java) {
                service.act("input", args, "root", {}, { dispatched.incrementAndGet() })
            }
            assertEquals("stale_snapshot", stale.code)
            assertEquals(1, dispatched.get())
        } finally { controller.destroy() }
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlRejectsSnapshotsFromAnotherSessionOrAnEarlierOrExpiredObservation() {
        val controller = phoneService()
        try {
            val service = controller.get()
            val root = phoneRoot(service)
            val node = phoneNode(root.window, "可点击控件").apply {
                isClickable = true
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            shadowOf(root).addChild(node)
            val dispatched = AtomicInteger()
            fun click(snapshot: String, session: String): PhoneFailure = assertThrows(PhoneFailure::class.java) {
                service.act("click", JSONObject().put("snapshot", snapshot).put("node", "n1"), session,
                    {}, { dispatched.incrementAndGet() })
            }
            val first = service.observe("root", { true }, {}).getString("snapshot")
            assertEquals("stale_snapshot", click(first, "other").code)
            val second = service.observe("root", { true }, {}).getString("snapshot")
            val third = service.observe("root", { true }, {}).getString("snapshot")
            assertNotEquals(second, third)
            assertEquals("stale_snapshot", click(second, "root").code)
            val expiring = service.observe("root", { true }, {}).getString("snapshot")
            shadowOf(Looper.getMainLooper())
                .idleFor(PhoneControl.SNAPSHOT_LIFETIME + 1, TimeUnit.MILLISECONDS)
            assertEquals("stale_snapshot", click(expiring, "root").code)
            assertEquals(0, dispatched.get())
            assertTrue(shadowOf(node).performedActions.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun phoneDeviceValidatesNewMethodsBeforeDispatch() {
        val control = PhoneControl(RuntimeEnvironment.getApplication())
        val invalid = listOf(
            "clipboard" to JSONObject().put("mode", "read").put("text", "unexpected"),
            "clipboard" to JSONObject().put("mode", "write"),
            "share" to JSONObject(),
            "share" to JSONObject().put("text", "x").put("path", "workspace:x"),
            "open_url" to JSONObject().put("url", "file:///data/private"),
            "open_url" to JSONObject().put("url", "https://user:pass@example.com"),
            "ask" to JSONObject().put("question", "choose").put("options", org.json.JSONArray(listOf("same", "same"))),
            "torch" to JSONObject().put("on", "true"),
            "vibrate" to JSONObject().put("ms", 0),
            "location" to JSONObject().put("fresh", 1),
            "tap" to JSONObject().put("screenshot", "x").put("x", 1.5).put("y", 1)
        )
        for ((method, args) in invalid) phoneFailure("invalid_args", control.execute(method, args, "root") { true })
        for (method in PhoneDevice.methods) {
            if (method in setOf("device", "sensors", "location", "vibrate")) {
                phoneFailure("wrong_thread", control.execute(method, JSONObject(), "root") { true })
            }
        }
        phoneFailure("wrong_thread", control.execute("clipboard", JSONObject().put("mode", "write").put("text", ""), "root") { true })
    }

    @Test fun phoneDeviceSensitiveSwitchesAreIndependentAndDefaultClosed() {
        val control = PhoneControl(RuntimeEnvironment.getApplication())
        PhoneDevice.permissions.forEach { assertFalse(control.allowed(it)) }
        control.setAllowed("clipboard", true)
        assertTrue(control.allowed("clipboard"))
        assertFalse(control.allowed("files"))
        assertFalse(control.enabled())
        control.setEnabled(true)
        control.setEnabled(false)
        assertTrue(control.allowed("clipboard"))
        control.setAllowed("clipboard", false)
        assertFalse(control.allowed("clipboard"))
    }

    @Test fun phoneDeviceRequiresOwnSwitchAndClipboardFocusWithoutAccessibility() {
        val device = PhoneDevice.get(RuntimeEnvironment.getApplication())
        var dispatched = 0
        fun read() = device.execute("clipboard", JSONObject().put("mode", "read"), {}, { dispatched++ }, {}, { true })
        assertEquals("capability_disabled", assertThrows(PhoneFailure::class.java) { read() }.code)
        device.setAllowed("clipboard", true)
        assertEquals("foreground_required", assertThrows(PhoneFailure::class.java) { read() }.code)
        device.setAllowed("files", true)
        val error = assertThrows(PhoneFailure::class.java) {
            device.validate("export", JSONObject().put("path", "workspace:x").put("extra", true))
        }
        assertEquals("invalid_args", error.code)
        assertEquals(0, dispatched)
        assertNull(PhoneService.current)
    }

    @Test fun phoneDeviceReadsInformationWithoutAccessibilityAndHandlesUnsupportedSensors() {
        val device = PhoneDevice.get(RuntimeEnvironment.getApplication())
        var result: JSONObject? = null
        device.execute("device", JSONObject(), {}, {}, { result = it }, { true })
        assertTrue(result!!.getBoolean("ok"))
        assertEquals(34, result!!.getInt("api"))
        assertFalse(result!!.has("androidId"))
        device.setAllowed("sensors", true)
        device.execute("sensors", JSONObject(), {}, {}, { result = it }, { true })
        assertTrue(result!!.has("sensors"))
        assertEquals("invalid_args", assertThrows(PhoneFailure::class.java) {
            device.execute("sensor", JSONObject().put("name", "made-up"), {}, {}, {}, { true })
        }.code)
    }

    @Test fun phoneFilesRejectsTraversalAndCredentialPaths() {
        assertEquals(listOf("notes", "hello.txt"), PhoneFiles.parts("notes/hello.txt"))
        assertTrue(PhoneFiles.parts("").isEmpty())
        for (path in listOf("../x", "a/../../x", "/tmp/x", "a//b", "a/./b", "a/.ssh/key", ".dsh/auth.json", "a\\b", "a\u0000b")) {
            assertEquals(path, "path_denied", assertThrows(PhoneFailure::class.java) { PhoneFiles.parts(path) }.code)
        }
    }

    @Test fun phoneDeviceAskCleanupDismissesWithoutInventingAnAnswer() {
        val controller = phoneService()
        try {
            val device = PhoneDevice.get(RuntimeEnvironment.getApplication())
            val result = AtomicReference<JSONObject?>()
            val args = JSONObject().put("question", "请选择").put("options", org.json.JSONArray(listOf("同意", "取消")))
            val cleanup = device.execute("ask", args, {}, {}, result::set, { true })!!
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            cleanup()
            assertFalse(dialog.isShowing)
            assertNull(result.get())
        } finally { controller.destroy() }
    }

    @Test fun phoneFilesRevokingDirectoryInvalidatesAnAlreadyQueuedGrant() {
        val files = PhoneFiles(RuntimeEnvironment.getApplication())
        val version = files.requestGrant()
        files.revoke()
        assertEquals("cancelled", assertThrows(PhoneFailure::class.java) {
            files.grant(Uri.parse("content://example.documents/tree/test"), Intent.FLAG_GRANT_READ_URI_PERMISSION, version)
        }.code)
        assertEquals("", files.directory())
    }

    private fun phoneImage(service: PhoneService, root: AccessibilityNodeInfo): JSONObject {
        val method = PhoneService::class.java.getDeclaredMethod("screen", AccessibilityNodeInfo::class.java).apply { isAccessible = true }
        val screen = method.invoke(service, root)
        val image = PhoneService::class.java.declaredClasses.single { it.simpleName == "Image" }
        val constructor = image.declaredConstructors.single().apply { isAccessible = true }
        val saved = constructor.newInstance("image-1", "root", { true }, android.os.SystemClock.elapsedRealtime() + 10000,
            screen, ReflectionHelpers.getField<Long>(service, "revision"),
            ReflectionHelpers.getField<Int>(screen, "width"), ReflectionHelpers.getField<Int>(screen, "height"))
        ReflectionHelpers.setField(service, "image", saved)
        return JSONObject().put("screenshot", "image-1").put("x", 20).put("y", 20)
    }

    @Test @Config(shadows = [PhoneAccess::class])
    fun phoneControlCoordinateGesturesInheritSensitiveAncestorsEvenOutsideTheirBounds() {
        val controller = phoneService()
        try {
            val service = controller.get(); val root = phoneRoot(service)
            val parent = phoneNode(root.window, "").apply { isPassword = true; setBoundsInScreen(Rect(80, 80, 90, 90)) }
            val child = phoneNode(root.window, "").apply { setBoundsInScreen(Rect(10, 10, 40, 40)) }
            shadowOf(root).addChild(parent); shadowOf(parent).addChild(child)
            val args = phoneImage(service, root); val dispatched = AtomicInteger()
            assertEquals("sensitive", assertThrows(PhoneFailure::class.java) {
                service.gesture("tap", args, "root", {}, { dispatched.incrementAndGet() }, {})
            }.code)
            assertEquals(0, dispatched.get())
            assertEquals("stale_snapshot", assertThrows(PhoneFailure::class.java) { service.gesture("tap", args, "root", {}, {}, {}) }.code)
        } finally { controller.destroy() }
    }

    @Implements(AccessibilityNodeInfo::class)
    class UnreadablePhoneNode : org.robolectric.shadows.ShadowAccessibilityNodeInfo() {
        @Implementation protected override fun getChildCount(): Int = 1
        @Implementation protected override fun getChild(index: Int): AccessibilityNodeInfo? = null
    }

    @Test @Config(shadows = [PhoneAccess::class, UnreadablePhoneNode::class])
    fun phoneControlCoordinateGesturesRejectAnUnreadableDeclaredChild() {
        val controller = phoneService()
        try {
            val service = controller.get(); val root = phoneRoot(service)
            val args = phoneImage(service, root)
            assertEquals("sensitive", assertThrows(PhoneFailure::class.java) { service.gesture("tap", args, "root", {}, { fail("unreadable branch dispatched") }, {}) }.code)
        } finally { controller.destroy() }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun phoneControlDisablingAnUnrelatedCapabilityDoesNotCancelQueuedDeviceWork() {
        val context = RuntimeEnvironment.getApplication(); val control = PhoneControl(context)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).apply { setKeyguardLocked(false); setIsDeviceLocked(false) }
        shadowOf(context.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        control.setEnabled(true); control.setAllowed("sensors", true); control.setAllowed("torch", true)
        val queued = CountDownLatch(1); val checks = AtomicInteger(); val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<JSONObject> { control.execute("sensors", JSONObject(), "root") { if (checks.incrementAndGet() >= 2) queued.countDown(); true } }
            assertTrue(queued.await(3, TimeUnit.SECONDS))
            control.setAllowed("torch", false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(result.get(3, TimeUnit.SECONDS).getBoolean("ok"))
        } finally { worker.shutdownNow(); control.setEnabled(false) }
    }

    private fun assistantEvent(id: Int, event: String): JSONObject = JSONObject()
        .put("version", 1).put("id", "00000000-0000-4000-8000-${id.toString().padStart(12, '0')}").put("event", event)

    private fun assistantStart(id: Int, purpose: String = "conversation"): JSONObject = assistantEvent(id, "start")
        .put("provider", "provider").put("model", "model").put("sessionId", "session")
        .put("purpose", purpose).put("startedAt", 1000)

    private fun assistantUsage(id: Int, input: Any? = null, output: Any? = null): JSONObject = assistantEvent(id, "usage")
        .put("usage", JSONObject().apply {
            if (input != null) put("inputTokens", input)
            if (output != null) put("outputTokens", output)
        })

    private fun assistantFinish(id: Int, outcome: String): JSONObject = assistantEvent(id, "finish")
        .put("outcome", outcome).put("endedAt", 1250)

    private fun assistantLedger(vararg events: JSONObject): File = File(folder.root, "assistant/usage.jsonl").apply {
        parentFile!!.mkdirs()
        writeText(events.joinToString("\n", postfix = if (events.isEmpty()) "" else "\n"))
    }

    @Test @Config(sdk = [26])
    fun assistantDefaultsMatchTheBundledDsStandardPersona() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val sources = mutableMapOf<String, String>()
        val paths = setOf(
            "lib/node_modules/@deepseek-ai/dsh-system-prompt/lib/index.js",
            "lib/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml",
            "lib/node_modules/@deepseek-ai/dsh-web-app/presets/standard.patch.yml",
        )
        java.util.zip.ZipInputStream(context.assets.open("core.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in paths) sources[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(paths, sources.keys)
        val defaults = AssistantStore.defaults
        assertEquals("", defaults.name)
        assertTrue(sources.getValue(paths.elementAt(0)).contains("text: \"${defaults.identity}\""))
        assertTrue(sources.getValue(paths.elementAt(1)).contains("personaPrefix: >-\n      ${defaults.prompt}"))
        assertTrue(sources.getValue(paths.elementAt(2)).contains("prefix: ${defaults.prompt}"))
    }

    @Test @Config(sdk = [26])
    fun assistantDefaultsDisplayWithoutBecomingLiteralOverrides() {
        val store = AssistantStore(folder.root)
        val defaults = AssistantStore.defaults
        assertEquals(defaults, store.readSettings().displayed())
        assertFalse(File(folder.root, "assistant/settings.json").exists())
        assertEquals(AssistantStore.Settings(), defaults.custom())
        val edited = defaults.copy(name = "  小禾  ", prompt = "  回答保留 {{原文}}  ")
        val saved = store.saveSettings(edited.custom())
        assertEquals(AssistantStore.Settings("小禾", prompt = "回答保留 {{原文}}"), saved)
        assertEquals(defaults.copy(name = "小禾", prompt = "回答保留 {{原文}}"), store.readSettings().displayed())
        assertEquals(AssistantStore.Settings(), store.saveSettings(AssistantStore.Settings().custom()))
        assertEquals(defaults, AssistantStore(folder.root).readSettings().displayed())
        assertEquals(AssistantStore.Settings(), store.saveSettings(defaults.custom()))
        assertEquals("", JSONObject(File(folder.root, "assistant/settings.json").readText()).getString("prompt"))
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsTrimPersistAndRestoreDefaultsWithoutChangingUsage() {
        val store = AssistantStore(folder.root)
        assertEquals(AssistantStore.Settings(), store.readSettings())
        val saved = store.saveSettings(AssistantStore.Settings("  小助手\n", " 身份 ", "\n回答指令\n"))
        assertEquals(AssistantStore.Settings("小助手", "身份", "回答指令"), saved)
        assertEquals(saved, AssistantStore(folder.root).readSettings())
        val json = JSONObject(File(folder.root, "assistant/settings.json").readText())
        assertEquals(1, json.getInt("version"))
        assertEquals("小助手", json.getString("name"))
        val ledger = assistantLedger(assistantStart(1))
        val before = ledger.readText()
        store.saveSettings(AssistantStore.Settings())
        assertEquals(AssistantStore.Settings(), AssistantStore(folder.root).readSettings())
        assertEquals("", JSONObject(File(folder.root, "assistant/settings.json").readText()).getString("prompt"))
        assertEquals(before, ledger.readText())
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsUseUtf16LimitsAndKeepSavedDataOnValidationFailure() {
        val store = AssistantStore(folder.root)
        val boundary = AssistantStore.Settings("😀".repeat(40), "身".repeat(2000), "令".repeat(16000))
        assertEquals(boundary, store.saveSettings(boundary.copy(name = "  ${boundary.name}  ")))
        listOf(boundary.copy(name = boundary.name + "x"), boundary.copy(identity = "x".repeat(2001)),
            boundary.copy(prompt = "x".repeat(16001))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.saveSettings(invalid) }
            assertEquals(boundary, store.readSettings())
        }
        val settings = File(folder.root, "assistant/settings.json")
        settings.delete()
        settings.mkdir()
        File(settings, "blocked").writeText("keep")
        assertThrows(Exception::class.java) { store.saveSettings(boundary) }
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsRecoverAtomicBackupAndReportBrokenSettings() {
        val store = AssistantStore(folder.root)
        val value = store.saveSettings(AssistantStore.Settings("原配置"))
        val settings = File(folder.root, "assistant/settings.json")
        File(settings.path + ".bak").writeText(settings.readText())
        settings.writeText("broken")
        assertEquals(value, store.readSettings())
        assertEquals("原配置", JSONObject(settings.readText()).getString("name"))
        assertFalse(File(settings.path + ".bak").exists())
        listOf("{", "{\"version\":2,\"name\":\"\",\"identity\":\"\",\"prompt\":\"\"}",
            "{\"version\":1,\"name\":false,\"identity\":\"\",\"prompt\":\"\"}",
            "{\"version\":\"1\",\"name\":\"\",\"identity\":\"\",\"prompt\":\"\"}").forEach { broken ->
            settings.writeText(broken)
            assertThrows(Exception::class.java) { store.readSettings() }
        }
        store.saveSettings(AssistantStore.Settings())
        assertEquals(AssistantStore.Settings(), store.readSettings())
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsRecoverLegacyBackupBeforeDirectReadersOpenTheBase() {
        val store = AssistantStore(folder.root)
        val value = store.saveSettings(AssistantStore.Settings("备份中的设置", "原身份", "原指令"))
        val settings = File(folder.root, "assistant/settings.json")
        val backup = File(settings.path + ".bak").apply { writeText(settings.readText()) }
        assertTrue(settings.delete())
        File(settings.path + ".new").writeText("{\"version\":1,")
        assertEquals(value, AssistantStore(folder.root).readSettings())
        val direct = JSONObject(settings.readText())
        assertEquals(value.name, direct.getString("name"))
        assertEquals(value.identity, direct.getString("identity"))
        assertEquals(value.prompt, direct.getString("prompt"))
        assertFalse(backup.exists())
        assertFalse(File(settings.path + ".new").exists())
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsIgnoreInterruptedTemporaryWritesAndKeepThePreviousBase() {
        val store = AssistantStore(folder.root)
        val previous = store.saveSettings(AssistantStore.Settings("已发布", "身份", "原指令"))
        val settings = File(folder.root, "assistant/settings.json")
        val before = settings.readText()
        val temporary = File(settings.path + ".new")
        listOf("", "{\"version\":1,", JSONObject(before).put("name", "未发布").toString()).forEach { interrupted ->
            temporary.writeText(interrupted)
            assertEquals(previous, AssistantStore(folder.root).readSettings())
            assertEquals(before, settings.readText())
            assertEquals(interrupted, temporary.readText())
        }
        val next = AssistantStore.Settings("新设置")
        assertEquals(next, store.saveSettings(next))
        assertEquals(next, AssistantStore(folder.root).readSettings())
        assertFalse(temporary.exists())
        assertTrue(settings.delete())
        temporary.writeText("{\"version\":1,")
        assertEquals(AssistantStore.Settings(), store.readSettings())
        assertFalse(settings.exists())
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsKeepThePreviousBaseWhenTemporaryWritingFails() {
        val store = AssistantStore(folder.root)
        val previous = store.saveSettings(AssistantStore.Settings("已发布"))
        val settings = File(folder.root, "assistant/settings.json")
        val before = settings.readText()
        val temporary = File(settings.path + ".new").apply { mkdir() }
        File(temporary, "blocked").writeText("keep")
        assertThrows(Exception::class.java) { store.saveSettings(AssistantStore.Settings("不应发布")) }
        assertEquals(before, settings.readText())
        assertEquals(previous, AssistantStore(folder.root).readSettings())
        assertFalse(File(settings.path + ".bak").exists())
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsKeepBackupAndBaseWhenLegacyRecoveryCannotPublish() {
        val store = AssistantStore(folder.root)
        store.saveSettings(AssistantStore.Settings("完整正式文件"))
        val settings = File(folder.root, "assistant/settings.json")
        val before = settings.readText()
        val backup = File(settings.path + ".bak").apply {
            writeText(JSONObject(before).put("name", "备份设置").toString())
        }
        val backupBefore = backup.readText()
        val temporary = File(settings.path + ".new").apply { mkdir() }
        File(temporary, "blocked").writeText("keep")
        assertThrows(Exception::class.java) { store.readSettings() }
        assertEquals(before, settings.readText())
        assertEquals(backupBefore, backup.readText())
        backup.writeText("{\"version\":1,")
        assertThrows(Exception::class.java) { store.readSettings() }
        assertEquals(before, settings.readText())
        assertEquals("{\"version\":1,", backup.readText())
        assertTrue(File(temporary, "blocked").delete())
        assertTrue(temporary.delete())
        assertEquals(AssistantStore.Settings(), store.saveSettings(AssistantStore.Settings()))
        assertEquals(AssistantStore.Settings(), store.readSettings())
        assertFalse(backup.exists())
    }

    @Test @Config(sdk = [26])
    fun assistantSettingsConcurrentWritersPublishCompleteJsonToDirectReaders() {
        val values = listOf(
            AssistantStore.Settings("初始设置", "初始身份", "初始指令"),
            AssistantStore.Settings("甲设置", "甲".repeat(2000), "甲".repeat(16000)),
            AssistantStore.Settings("乙设置", "乙".repeat(2000), "乙".repeat(16000)),
        )
        AssistantStore(folder.root).saveSettings(values[0])
        val settings = File(folder.root, "assistant/settings.json")
        val workers = java.util.concurrent.Executors.newFixedThreadPool(3)
        val ready = CountDownLatch(1)
        val writing = CountDownLatch(2)
        val reads = AtomicInteger()
        try {
            // This reader deliberately bypasses AssistantStore and its lock, as Node readFile does.
            val reader = workers.submit {
                ready.countDown()
                while (writing.count > 0 || reads.get() < 100) {
                    val text = settings.readText()
                    assertTrue("The base must never be empty", text.isNotEmpty())
                    val json = JSONObject(text)
                    val expected = values.single { it.name == json.getString("name") }
                    assertEquals(1, json.getInt("version"))
                    assertEquals(expected.identity, json.getString("identity"))
                    assertEquals(expected.prompt, json.getString("prompt"))
                    reads.incrementAndGet()
                }
            }
            val writers = values.drop(1).map { value ->
                workers.submit {
                    try {
                        assertTrue(ready.await(5, TimeUnit.SECONDS))
                        val store = AssistantStore(folder.root)
                        repeat(80) { assertEquals(value, store.saveSettings(value)) }
                    } finally { writing.countDown() }
                }
            }
            writers.forEach { it.get(20, TimeUnit.SECONDS) }
            reader.get(20, TimeUnit.SECONDS)
            assertTrue(reads.get() >= 100)
            assertTrue(AssistantStore(folder.root).readSettings() in values.drop(1))
            assertFalse(File(settings.path + ".bak").exists())
            assertFalse(File(settings.path + ".new").exists())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun assistantUsageSeparatesConfirmedRequestsFromAttemptsAndDefaultsLegacyRecords() {
        val ledger = assistantLedger(assistantStart(1),
            assistantStart(2).put("dispatch", "http-request"), assistantUsage(2, 3, 5), assistantFinish(2, "success"),
            assistantStart(3).put("dispatch", "adapter-attempt"), assistantUsage(3, output = 7))
        val store = AssistantStore(folder.root)
        val initial = store.readUsage()
        assertTrue(initial.warnings.isEmpty())
        assertEquals(3, initial.summary.count)
        assertEquals(1, initial.summary.requests)
        assertEquals(2, initial.summary.attempts)
        assertEquals("adapter-attempt", initial.calls[0].dispatch)
        assertEquals("http-request", initial.calls[1].dispatch)
        assertEquals("adapter-attempt", initial.calls[2].dispatch)
        listOf<Any>("future", JSONObject.NULL, 1).forEachIndexed { index, invalid ->
            ledger.appendText(assistantStart(index + 4, "other").put("dispatch", invalid).toString() + "\n")
        }
        val snapshot = store.readUsage()
        val summary = snapshot.summary
        assertEquals(6, summary.count)
        assertEquals(1, summary.requests)
        assertEquals(5, summary.attempts)
        assertEquals(summary.count, summary.requests + summary.attempts)
        assertEquals(1, summary.success)
        assertEquals(5, summary.unknown)
        assertEquals("3", summary.inputTokens.toString())
        assertEquals("12", summary.outputTokens.toString())
        assertEquals("15", summary.knownTokens.toString())
        assertEquals(1, snapshot.sources.getValue("conversation").requests)
        assertEquals(2, snapshot.sources.getValue("conversation").attempts)
        assertEquals(0, snapshot.sources.getValue("other").requests)
        assertEquals(3, snapshot.sources.getValue("other").attempts)
        assertTrue(snapshot.calls.drop(3).all { it.dispatch == "adapter-attempt" })
        assertEquals(3, snapshot.warnings.size)
        assertTrue(snapshot.warnings.all { it.contains("发送分类不合法") })
    }

    @Test fun assistantUsageReplacesSnapshotsAndKeepsCacheReasoningAndFailedUsage() {
        val usage = assistantUsage(1, 12, 8).apply {
            getJSONObject("usage").put("cacheReadTokens", 100).put("cacheWriteTokens", 4)
                .put("reasoningTokens", 3).put("totalTokens", 124)
        }
        assistantLedger(assistantStart(1), assistantUsage(1, 2, 1), assistantStart(2, "compaction"),
            usage, assistantUsage(2, 5, 7), assistantFinish(2, "success"), assistantFinish(1, "failed"))
        val snapshot = AssistantStore(folder.root).readUsage()
        assertTrue(snapshot.warnings.isEmpty())
        val summary = snapshot.summary
        assertEquals(2, summary.count)
        assertEquals(1, summary.success)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.unknown)
        assertEquals("17", summary.inputTokens.toString())
        assertEquals("15", summary.outputTokens.toString())
        assertEquals("100", summary.cacheReadTokens.toString())
        assertEquals("4", summary.cacheWriteTokens.toString())
        assertEquals("3", summary.reasoningTokens.toString())
        assertEquals("124", summary.totalTokens.toString())
        assertEquals("121", summary.knownInputTokens.toString())
        assertEquals("136", summary.knownTokens.toString())
        assertEquals("12", snapshot.sources.getValue("conversation").inputTokens.toString())
        val call = snapshot.calls.first()
        assertEquals("provider", call.provider)
        assertEquals("model", call.model)
        assertEquals("session", call.sessionId)
        assertEquals(1000L, call.startedAt)
        assertEquals(250L, call.duration)
    }

    @Test fun assistantUsageKeepsPartialFieldsAndCountsUnknownCancelledAndIncomplete() {
        assistantLedger(assistantStart(1), assistantUsage(1, output = 7), assistantFinish(1, "cancelled"),
            assistantStart(2, "session-title"), assistantUsage(2, 2, 3), assistantUsage(2, output = 4),
            assistantStart(3, "other"), assistantEvent(3, "usage"), assistantFinish(3, "incomplete"),
            assistantStart(4).apply { remove("sessionId") }, assistantUsage(4, 0, 0), assistantFinish(4, "success"))
        val snapshot = AssistantStore(folder.root).readUsage()
        assertTrue(snapshot.warnings.isEmpty())
        assertEquals(4, snapshot.summary.count)
        assertEquals(3, snapshot.summary.unknown)
        assertEquals(1, snapshot.summary.cancelled)
        assertEquals(2, snapshot.summary.incomplete)
        assertEquals(1, snapshot.summary.success)
        assertEquals("0", snapshot.summary.inputTokens.toString())
        assertEquals("11", snapshot.summary.outputTokens.toString())
        assertNull(snapshot.calls[0].usage!!.inputTokens)
        assertEquals(7L, snapshot.calls[0].usage!!.outputTokens)
        assertNull(snapshot.calls[1].usage!!.inputTokens)
        assertNull(snapshot.calls[1].endedAt)
        assertNull(snapshot.calls[3].sessionId)
        assertNull(snapshot.sources.getValue("other").inputTokens)
        assertNull(snapshot.sources.getValue("other").outputTokens)
        assertNull(snapshot.sources.getValue("session-title").inputTokens)
    }

    @Test fun assistantUsageSalvagesValidFieldsButRejectsNonIntegerNegativeOrStringTokens() {
        val values = listOf<Any>(-1, 1.5, "12", true, JSONObject.NULL)
        val events = values.flatMapIndexed { index, invalid ->
            listOf(assistantStart(index + 1), assistantUsage(index + 1, invalid, 6).apply {
                getJSONObject("usage").put("cacheReadTokens", -1).put("cacheWriteTokens", 2).put("reasoningTokens", "2")
            })
        }
        assistantLedger(*events.toTypedArray())
        val snapshot = AssistantStore(folder.root).readUsage()
        assertEquals(5, snapshot.summary.unknown)
        assertNull(snapshot.summary.inputTokens)
        assertEquals("30", snapshot.summary.outputTokens.toString())
        assertNull(snapshot.summary.cacheReadTokens)
        assertEquals("10", snapshot.summary.cacheWriteTokens.toString())
        assertNull(snapshot.summary.reasoningTokens)
        assertEquals(15, snapshot.warnings.size)
    }

    @Test fun assistantUsageIgnoresOrphansUnknownEventsAndBrokenFinishesWithoutInventingSuccess() {
        val ledger = assistantLedger(assistantUsage(90, 1, 1), assistantFinish(90, "success"),
            assistantStart(1), assistantStart(1), assistantEvent(1, "future"),
            assistantFinish(1, "future"), assistantFinish(1, "success").put("endedAt", "1250"),
            assistantFinish(1, "success").put("endedAt", 999),
            assistantStart(2).put("version", 2), assistantFinish(2, "success"),
            assistantStart(3).put("id", "bad-id"), assistantFinish(3, "success"))
        ledger.appendText("broken\n")
        ledger.appendText(assistantFinish(1, "success").toString().replace("\"outcome\":", "outcome:") + "\n")
        // Even syntactically valid JSON is not committed until its newline is written.
        ledger.appendText(assistantFinish(1, "success").toString())
        val snapshot = AssistantStore(folder.root).readUsage()
        assertEquals(1, snapshot.summary.count)
        assertEquals(0, snapshot.summary.success)
        assertEquals(1, snapshot.summary.incomplete)
        assertEquals(1, snapshot.summary.unknown)
        assertNull(snapshot.summary.inputTokens)
        assertEquals(14, snapshot.warnings.size)
        assertTrue(snapshot.warnings.last().contains("末尾"))
    }

    @Test fun assistantUsageRefreshKeepsOldSnapshotAndRetainsUsageAcrossRestart() {
        val ledger = assistantLedger(assistantStart(1), assistantUsage(1, 2, 5))
        val store = AssistantStore(folder.root)
        val before = store.readUsage()
        ledger.appendText(assistantFinish(1, "cancelled").toString() + "\n")
        val after = AssistantStore(folder.root).readUsage()
        assertEquals(1, before.summary.incomplete)
        assertEquals(0, before.summary.cancelled)
        assertEquals(0, after.summary.incomplete)
        assertEquals(1, after.summary.cancelled)
        assertEquals("5", after.summary.outputTokens.toString())
        assertEquals(250L, after.calls.single().duration)
    }

    @Test fun assistantUsageSumsWithoutOverflowAndReportsUnreadableLedgers() {
        val store = AssistantStore(folder.root)
        assertEquals(0, store.readUsage().summary.count)
        assertNull(store.readUsage().summary.inputTokens)
        val ledger = assistantLedger(assistantStart(1), assistantUsage(1, Long.MAX_VALUE, Long.MAX_VALUE),
            assistantStart(2, "other"), assistantUsage(2, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals("18446744073709551614", store.readUsage().summary.inputTokens.toString())
        assertEquals("18446744073709551614", store.readUsage().summary.outputTokens.toString())
        ledger.delete()
        ledger.mkdir()
        assertThrows(Exception::class.java) { store.readUsage() }
    }
}
