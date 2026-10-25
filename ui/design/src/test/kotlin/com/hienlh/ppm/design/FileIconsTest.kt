package com.hienlh.ppm.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which glyph a name gets, as the web's own tests have it (`tests/unit/web/file-icons.test.ts`),
 * against the tables `buildFileIcons` ships.
 */
class FileIconsTest {
    private val dir = File(System.getProperty("ppm.fileIcons") ?: error("run through Gradle: ppm.fileIcons is unset"), "file-icons")
    private val json = Json.parseToJsonElement(File(dir, "tables.json").readText()) as JsonObject
    private val icons = FileIconTables.parse(File(dir, "tables.json").readText())

    private fun table(vararg keys: String): Map<String, String> =
        keys.fold(json) { obj, key -> obj[key] as JsonObject }.mapValues { (it.value as JsonPrimitive).content }

    private val extensions = table("extensions")
    private val filenames = table("filenames")
    private fun string(key: String) = (json[key] as JsonPrimitive).content
    private val defaultFile = string("defaultFile")

    @Test
    fun `every glyph the tables name is drawn, and every light drawing has a dark one`() {
        val named = buildSet {
            add(defaultFile); add(string("defaultFolder")); add(string("defaultFolderOpen"))
            for (key in listOf("extensions", "filenames", "folders", "foldersOpen", "dbEngines")) addAll(table(key).values)
            for (framework in listOf("nest", "angular")) {
                addAll(table("frameworkExtensions", framework).values)
                addAll(table("frameworkFilenames", framework).values)
            }
        }
        assertEquals(emptyList<String>(), named.filterNot { File(dir, "dark/$it.svg").isFile })
        val light = (json["light"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue("${light.size} light drawings", light.size > 50)
        assertEquals(emptyList<String>(), light.filterNot { File(dir, "dark/$it.svg").isFile && File(dir, "light/$it.svg").isFile })
    }

    @Test
    fun `a whole filename beats its extension, and only the last segment counts`() {
        assertEquals(filenames["package.json"], icons.file("package.json"))
        assertNotEquals(extensions["json"], icons.file("package.json"))
        assertEquals(extensions["tsx"], icons.file("src/web/lib/file-icons.tsx"))
        assertEquals(extensions["tsx"], icons.file("src\\web\\lib\\file-icons.tsx"))
    }

    @Test
    fun `a longer suffix matches first, up to three segments, starting one segment in`() {
        assertEquals(extensions["test.ts"], icons.file("use-chat.test.ts"))
        assertEquals(extensions["d.ts"], icons.file("shared.d.ts"))
        assertEquals(extensions["buf.gen.yaml"], icons.file("service.buf.gen.yaml"))
        assertEquals(extensions["yaml"], icons.file("buf.gen.yaml"))
    }

    @Test
    fun `names are case-insensitive, dotfiles resolve, and anything else gets the default`() {
        assertEquals(icons.file("Dockerfile"), icons.file("DOCKERFILE"))
        assertEquals(icons.file("readme.md"), icons.file("README.MD"))
        assertEquals("file-type-npm", icons.file(".npmrc"))
        assertNotEquals(defaultFile, icons.file(".prettierrc"))
        assertNotEquals(defaultFile, icons.file(".gitignore"))
        for (name in listOf("mystery.qqq", "noextension", "", "constructor", "x.__proto__")) assertEquals(name, defaultFile, icons.file(name))
        for (name in listOf("app.tsx", "app.jsx", "main.go", "lib.rs", "bun.lock", "bunfig.toml", "Dockerfile", ".env.local", "Cargo.lock", "gradlew", "a.kts")) {
            assertNotEquals(name, defaultFile, icons.file(name))
        }
    }

    @Test
    fun `a folder has an open glyph of its own`() {
        assertEquals(table("folders")["src"], icons.folder("src"))
        assertEquals(table("foldersOpen")["src"], icons.folder("src", open = true))
        assertNotEquals(icons.folder("src"), icons.folder("src", open = true))
        assertEquals(string("defaultFolder"), icons.folder("whatever"))
        assertEquals(string("defaultFolderOpen"), icons.folder("whatever", open = true))
    }

    @Test
    fun `every database engine has its own logo, and an unknown one the SQL glyph`() {
        val logos = listOf("postgres", "mysql", "mariadb", "sqlite").map(icons::dbEngine)
        assertEquals(4, logos.toSet().size)
        assertTrue(extensions["sql"] !in logos)
        assertEquals(extensions["sql"], icons.dbEngine("oracle"))
        assertEquals(extensions["sql"], icons.dbEngine("constructor"))
    }

    @Test
    fun `a framework takes the suffixes it claims, and a more specific suffix still wins`() {
        for (name in listOf("app.service.ts", "app.module.ts", "app.controller.ts")) assertEquals(extensions["ts"], icons.file(name))
        assertEquals("file-type-nest-controller-ts", icons.file("app.controller.ts", IconFramework.NEST))
        assertEquals("file-type-nest-module-ts", icons.file("app.module.ts", IconFramework.NEST))
        assertEquals("file-type-nest-service-ts", icons.file("app.service.ts", IconFramework.NEST))
        val angular = table("frameworkExtensions", "angular")
        assertEquals(angular["module.ts"], icons.file("app.module.ts", IconFramework.ANGULAR))
        assertNotEquals(icons.file("app.service.ts", IconFramework.NEST), icons.file("app.service.ts", IconFramework.ANGULAR))
        assertEquals(angular["component.ts"], icons.file("hero.component.ts", IconFramework.ANGULAR))
        assertEquals(extensions["ts"], icons.file("hero.component.ts", IconFramework.NEST))
        assertEquals(extensions["spec.ts"], icons.file("app.controller.spec.ts", IconFramework.NEST))
    }

    @Test
    fun `the framework is the one package json depends on, Nest first`() {
        assertEquals(IconFramework.NEST, IconFramework.of("""{"dependencies":{"@nestjs/core":"^10"}}"""))
        assertEquals(IconFramework.ANGULAR, IconFramework.of("""{"devDependencies":{"@angular/core":"^18"}}"""))
        assertEquals(IconFramework.NEST, IconFramework.of("""{"peerDependencies":{"@angular/core":"*"},"dependencies":{"@nestjs/core":"*"}}"""))
        for (text in listOf("""{"dependencies":{"react":"18"}}""", """{"dependencies":["@nestjs/core"]}""", "null", "[]", "not json", "")) {
            assertEquals(text, null, IconFramework.of(text))
        }
    }
}
