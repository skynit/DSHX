package local.dshx.app

import org.json.JSONObject
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.util.AndroidRuntimeException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
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
import java.util.concurrent.Executor
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
        for (name in listOf("start.mjs", "plugins.mjs", "lib/node_modules/pnpm/dist/pnpm.cjs", "lib/node_modules/npm/lib/cli.js")) {
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
