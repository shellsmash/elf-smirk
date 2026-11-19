package com.aspe

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component as AwtComponent
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

data class ExportedPreviewComponent(
    val name: String,
    val image: BufferedImage,
    val file: File
)

private data class LivePreviewCapture(
    val name: String,
    val image: BufferedImage
)

class QuickExportAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Export Preview Editor Images")
            .withDescription("Select a folder to save each rendered preview at normal size")

        val chosenDir = FileChooser.chooseFile(descriptor, project, null) ?: return
        val outputDir = File(chosenDir.path).apply { mkdirs() }

        val captures = collectLivePreviewCaptures(project)
        if (captures.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No rendered preview images were found in the active Preview editor.",
                "Quick Export"
            )
            return
        }

        val components = mutableListOf<ExportedPreviewComponent>()

        captures.forEachIndexed { index, capture ->
            val baseLabel = capture.name.ifBlank { "Preview_${index + 1}" }
            val safeName = sanitizeFileName(baseLabel)
            val outputFile = uniqueOutputFile(outputDir, safeName, "png")

            val written = ImageIO.write(capture.image, "png", outputFile)
            if (written) {
                components.add(
                    ExportedPreviewComponent(
                        name = baseLabel,
                        image = capture.image,
                        file = outputFile
                    )
                )
            }
        }

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputDir)?.refresh(false, true)

        Messages.showInfoMessage(
            project,
            "Exported ${components.size} preview image(s) at normal size to:\n${outputDir.absolutePath}",
            "Quick Export Complete"
        )
    }

    private fun collectLivePreviewCaptures(project: com.intellij.openapi.project.Project): List<LivePreviewCapture> {
        val surfaces = mutableListOf<Any>()

        val fileEditorManager = FileEditorManager.getInstance(project)
        val candidateEditors: List<FileEditor> = buildList {
            fileEditorManager.selectedEditor?.let { add(it) }
            addAll(fileEditorManager.allEditors)
        }.distinct()

        for (editor in candidateEditors) {
            findDesignSurfacesInObject(editor, surfaces, visited = HashSet())
            try {
                findDesignSurfacesInAwtTree(editor.component, surfaces)
            } catch (_: Throwable) {
            }
        }

        val toolWindowManager = ToolWindowManager.getInstance(project)
        for (id in toolWindowManager.toolWindowIds) {
            val tw = toolWindowManager.getToolWindow(id) ?: continue
            try {
                tw.contentManager.contents.forEach { content ->
                    findDesignSurfacesInObject(content.component, surfaces, visited = HashSet())
                    findDesignSurfacesInAwtTree(content.component, surfaces)
                }
            } catch (_: Throwable) {
            }
        }

        val results = mutableListOf<LivePreviewCapture>()
        for (surface in surfaces.distinct()) {
            val surfaceScale = extractScaleFactor(surface)

            val targets = buildList {
                addAll(invokeListGetter(surface, "getSceneViews"))
                if (isEmpty()) {
                    for (mgr in invokeListGetter(surface, "getSceneManagers")) {
                        addAll(invokeListGetter(mgr, "getSceneViews"))
                        invokeZeroArg(mgr, "getSceneView")?.let { add(it) }
                        if (invokeZeroArg(mgr, "getSceneView") == null && invokeListGetter(mgr, "getSceneViews").isEmpty()) {
                            add(mgr)
                        }
                    }
                }
                if (isEmpty()) {
                    addAll(invokeListGetter(surface, "getModels"))
                }
            }

            for (target in targets) {
                val targetScale = extractScaleFactor(target) ?: surfaceScale
                val rawImage = extractBufferedImage(target)
                    ?: (target as? AwtComponent)?.let { paintAwtComponent(it) }
                    ?: continue

                // Normalize back to 1.0x normal size if the image was captured at a zoom/scale factor
                val normalImage = normalizeToNormalScale(rawImage, targetScale)
                val name = extractPreviewName(target)
                results.add(LivePreviewCapture(name = name, image = normalImage))
            }
        }

        return results
    }

    /**
     * Reads zoom/scale (e.g. 1.0 = 100%, 2.0 = 200%, 0.5 = 50%) if exposed on the surface or SceneView.
     * Returns null when no valid positive scale factor is found.
     */
    private fun extractScaleFactor(target: Any): Double? {
        for (getter in listOf("getScale", "getZoom", "getScaleFactor")) {
            val value = invokeZeroArg(target, getter) ?: continue
            val scale = when (value) {
                is Number -> value.toDouble()
                else -> null
            }
            if (scale != null && scale > 0.05 && scale < 20.0) {
                return scale
            }
        }
        return null
    }

    /**
     * Resizes an image back to 1.0x (normal) dimensions when scale != 1.0.
     * If scale is 1.0 or unavailable, returns the original image untouched.
     */
    private fun normalizeToNormalScale(image: BufferedImage, scale: Double?): BufferedImage {
        if (scale == null || kotlin.math.abs(scale - 1.0) < 0.01) {
            return image
        }

        val targetWidth = kotlin.math.max(1, kotlin.math.round(image.width / scale).toInt())
        val targetHeight = kotlin.math.max(1, kotlin.math.round(image.height / scale).toInt())

        if (targetWidth == image.width && targetHeight == image.height) {
            return image
        }

        val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val g2d = resized.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(image, 0, 0, targetWidth, targetHeight, null)
        } finally {
            g2d.dispose()
        }
        return resized
    }

    private fun findDesignSurfacesInAwtTree(root: AwtComponent?, out: MutableList<Any>) {
        if (root == null) return
        if (isDesignSurface(root)) {
            out.add(root)
        }
        if (root is java.awt.Container) {
            for (child in root.components) {
                findDesignSurfacesInAwtTree(child, out)
            }
        }
    }

    private fun findDesignSurfacesInObject(
        obj: Any?,
        out: MutableList<Any>,
        visited: MutableSet<Int>,
        depth: Int = 0
    ) {
        if (obj == null || depth > 4) return
        val identity = System.identityHashCode(obj)
        if (!visited.add(identity)) return

        if (isDesignSurface(obj)) {
            out.add(obj)
            return
        }

        val getters = listOf("getSurface", "getDesignSurface", "getComponent", "getPreviewEditor")
        for (getterName in getters) {
            val child = invokeZeroArg(obj, getterName) ?: continue
            findDesignSurfacesInObject(child, out, visited, depth + 1)
        }
    }

    private fun isDesignSurface(obj: Any): Boolean {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            if (cls.name == "com.android.tools.idea.common.surface.DesignSurface" ||
                cls.simpleName == "DesignSurface" ||
                cls.simpleName == "NlDesignSurface"
            ) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun extractBufferedImage(sceneViewOrModel: Any): BufferedImage? {
        if (sceneViewOrModel is BufferedImage) return sceneViewOrModel

        val unwrapped = unwrapToBufferedImage(sceneViewOrModel)
        if (unwrapped != null) return unwrapped

        val directGetters = listOf(
            "getRenderedImage",
            "getImage",
            "getBufferedImage",
            "getCopy"
        )
        for (name in directGetters) {
            val value = invokeZeroArg(sceneViewOrModel, name) ?: continue
            val img = unwrapToBufferedImage(value)
            if (img != null) return img
        }

        val containerGetters = listOf(
            "getRenderResult",
            "getSceneManager",
            "getSceneView",
            "getModel"
        )
        for (getter in containerGetters) {
            val sub = invokeZeroArg(sceneViewOrModel, getter) ?: continue
            if (sub === sceneViewOrModel) continue
            val img = extractBufferedImage(sub)
            if (img != null) return img
        }

        return null
    }

    private fun unwrapToBufferedImage(candidate: Any): BufferedImage? {
        if (candidate is BufferedImage) return candidate

        for (getter in listOf("getCopy", "getBufferedImage", "getImage", "getRenderedImage")) {
            val res = invokeZeroArg(candidate, getter)
            if (res is BufferedImage) return res
        }

        val inner = invokeZeroArg(candidate, "getRenderResult")
        if (inner != null && inner !== candidate) {
            val nested = extractBufferedImage(inner)
            if (nested != null) return nested
        }

        val bitmapObj = invokeZeroArg(candidate, "getRenderedBitmap")
            ?: invokeZeroArg(candidate, "getBitmap")
        if (bitmapObj != null && bitmapObj !== candidate) {
            return unwrapToBufferedImage(bitmapObj)
        }

        return null
    }

    private fun paintAwtComponent(comp: AwtComponent): BufferedImage? {
        if (!comp.isShowing || comp.width <= 0 || comp.height <= 0) return null
        var img: BufferedImage? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (!comp.isShowing || comp.width <= 0 || comp.height <= 0) return@invokeAndWait
            val buffer = BufferedImage(comp.width, comp.height, BufferedImage.TYPE_INT_ARGB)
            val g2d = buffer.createGraphics()
            try {
                comp.paintAll(g2d)
            } finally {
                g2d.dispose()
            }
            img = buffer
        }
        return img
    }

    private fun extractPreviewName(sceneViewOrModel: Any): String {
        for (getter in listOf("getDisplayName", "getName", "getLabel")) {
            val raw = invokeZeroArg(sceneViewOrModel, getter) as? String
            if (!raw.isNullOrBlank()) return raw
        }

        for (getter in listOf("getViewModel", "getModel", "getSceneManager")) {
            val nested = invokeZeroArg(sceneViewOrModel, getter) ?: continue
            for (nestedGetter in listOf("getDisplayName", "getName", "getLabel")) {
                val raw = invokeZeroArg(nested, nestedGetter) as? String
                if (!raw.isNullOrBlank()) return raw
            }
        }

        return sceneViewOrModel.javaClass.simpleName
    }

    private fun invokeListGetter(target: Any, methodName: String): List<Any> {
        val raw = invokeZeroArg(target, methodName) ?: return emptyList()
        return when (raw) {
            is Collection<*> -> raw.filterNotNull()
            is Array<*> -> raw.filterNotNull()
            else -> emptyList()
        }
    }

    private fun invokeZeroArg(target: Any, methodName: String): Any? {
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: return null
            method.isAccessible = true
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .ifEmpty { "preview" }
    }

    private fun uniqueOutputFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }
}