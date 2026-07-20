package com.agentos.shell.tools

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * REAL .docx / .pptx / .xlsx WRITERS — no libraries.
 *
 * An Office file is just a ZIP of XML parts, so we can emit genuine, openable documents on-device with
 * zero dependencies. That matters because the alternative (Google Workspace API) can't be styled beyond
 * named styles, and can't work offline or without a Google account.
 *
 * These are DESIGNED, not just valid: a branded accent colour, real typographic hierarchy, generous
 * spacing, styled table headers — the same visual language as the HTML/PDF path, so whichever format the
 * user picks, the result looks like it came from the same studio.
 */
object Ooxml {
    /**
     * The look is DESIGNED PER DOCUMENT by the model, not hardcoded here — a legal memo, a kids' workshop
     * flyer and a fintech pitch should not share one palette. DocForge asks for a theme that suits the
     * actual content and passes it in; these values are only the neutral fallback for when that fails.
     * All colours are 6-digit hex WITHOUT the leading #.
     */
    data class Theme(
        val accent: String = "2F6BFF",
        val ink: String = "17171A",
        val muted: String = "6B6B70",
        val deckBg: String = "101014",
        val deckInk: String = "F5F5F7",
        val font: String = "Arial",
        val titleSize: Int = 32,      // pt, deck titles
        val bodySize: Int = 16        // pt, deck body
    )

    private val FALLBACK = Theme()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun zip(parts: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            parts.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    // ── shared block model ───────────────────────────────────────────────────────────────────────
    /** kind: title | h1 | h2 | body | bullet | quote */
    data class Block(val kind: String, val text: String)

    /** Parse light markdown into typed blocks — the same input shape every format consumes. */
    fun blocks(body: String): List<Block> = body.replace("\r", "").split("\n").mapNotNull { raw ->
        val t = raw.trim()
        when {
            t.isEmpty() -> null
            t.startsWith("### ") || t.startsWith("## ") -> Block("h2", t.substringAfter("# ").trim())
            t.startsWith("# ") -> Block("h1", t.removePrefix("# ").trim())
            t.startsWith("> ") -> Block("quote", t.removePrefix("> ").trim())
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> Block("bullet", t.drop(2).trim())
            else -> Block("body", t)
        }
    }.map { Block(it.kind, it.text.replace("**", "").replace("__", "")) }

    // ── DOCX ─────────────────────────────────────────────────────────────────────────────────────
    fun docx(title: String, body: String, th: Theme = FALLBACK): ByteArray {
        val bs = blocks(body)
        val sb = StringBuilder()
        // Cover title with an accent rule underneath.
        sb.append(para(esc(title), size = 56, color = th.ink, bold = true, spaceAfter = 60, font = th.font))
        sb.append(rule(th.accent))
        bs.forEach { b ->
            when (b.kind) {
                "h1" -> sb.append(para(esc(b.text), size = 32, color = th.accent, bold = true, spaceBefore = 320, spaceAfter = 120, font = th.font))
                "h2" -> sb.append(para(esc(b.text), size = 26, color = th.ink, bold = true, spaceBefore = 240, spaceAfter = 100, font = th.font))
                "quote" -> sb.append(para(esc(b.text), size = 24, color = th.muted, italic = true, indent = 480, spaceAfter = 140, font = th.font))
                "bullet" -> sb.append(bullet(esc(b.text), th))
                else -> sb.append(para(esc(b.text), size = 22, color = th.ink, spaceAfter = 140, lineRule = 300, font = th.font))
            }
        }
        val doc = XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>" + sb + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>" +
            "</w:body></w:document>"

        val numbering = XML + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/>" +
            "<w:lvlText w:val=\"•\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"${th.font}\"/></w:rPr></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"

        return zip(listOf(
            "[Content_Types].xml" to (XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
                "</Types>"),
            "_rels/.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>"),
            "word/_rels/document.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" +
                "</Relationships>"),
            "word/numbering.xml" to numbering,
            "word/document.xml" to doc))
    }

    private fun para(text: String, size: Int, color: String, bold: Boolean = false, italic: Boolean = false,
                     spaceBefore: Int = 0, spaceAfter: Int = 0, indent: Int = 0, lineRule: Int = 0,
                     font: String = "Arial"): String {
        val ind = if (indent > 0) "<w:ind w:left=\"$indent\"/>" else ""
        val line = if (lineRule > 0) "<w:spacing w:before=\"$spaceBefore\" w:after=\"$spaceAfter\" w:line=\"$lineRule\" w:lineRule=\"auto\"/>"
                   else "<w:spacing w:before=\"$spaceBefore\" w:after=\"$spaceAfter\"/>"
        return "<w:p><w:pPr>$line$ind</w:pPr><w:r><w:rPr>" +
            (if (bold) "<w:b/>" else "") + (if (italic) "<w:i/>" else "") +
            "<w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\"/>" +
            "</w:rPr><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
    }

    private fun bullet(text: String, th: Theme): String =
        "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>" +
            "<w:spacing w:after=\"80\" w:line=\"280\" w:lineRule=\"auto\"/></w:pPr>" +
            "<w:r><w:rPr><w:color w:val=\"${th.ink}\"/><w:sz w:val=\"22\"/><w:rFonts w:ascii=\"${th.font}\" w:hAnsi=\"${th.font}\"/></w:rPr>" +
            "<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    /** A thin accent rule — the signature that makes the page look designed. */
    private fun rule(accent: String): String =
        "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"18\" w:space=\"1\" w:color=\"$accent\"/></w:pBdr>" +
            "<w:spacing w:after=\"320\"/></w:pPr></w:p>"

    // ── XLSX ─────────────────────────────────────────────────────────────────────────────────────
    fun xlsx(rows: List<List<String>>, th: Theme = FALLBACK): ByteArray {
        val cols = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val sb = StringBuilder()
        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\" ht=\"${if (r == 0) 26 else 18}\" customHeight=\"1\">")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                val num = v.trim().toDoubleOrNull()
                val style = if (r == 0) 1 else 0
                if (num != null && r > 0)
                    sb.append("<c r=\"$ref\" s=\"$style\"><v>$num</v></c>")
                else
                    sb.append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
            }
            sb.append("</row>")
        }
        val widths = StringBuilder("<cols>")
        for (c in 0 until cols) widths.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"22\" customWidth=\"1\"/>")
        widths.append("</cols>")

        val sheet = XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>" +
            widths + "<sheetData>" + sb + "</sheetData>" +
            "<autoFilter ref=\"A1:" + colName(cols - 1) + rows.size + "\"/></worksheet>"

        // Style 1 = branded header: white bold text on the accent fill.
        val styles = XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><color rgb=\"FF${th.ink}\"/><name val=\"${th.font}\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"${th.font}\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${th.accent}\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"center\"/></xf></cellXfs></styleSheet>"

        return zip(listOf(
            "[Content_Types].xml" to (XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                "</Types>"),
            "_rels/.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"),
            "xl/workbook.xml" to (XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"),
            "xl/_rels/workbook.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>"),
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
    }

    private fun colName(i: Int): String {
        var n = i; val sb = StringBuilder()
        while (n >= 0) { sb.insert(0, ('A' + (n % 26))); n = n / 26 - 1 }
        return sb.toString()
    }

    // ── PPTX ─────────────────────────────────────────────────────────────────────────────────────
    /** [slides] = title to body (body lines starting with "-" become bullets). */
    fun pptx(slides: List<Pair<String, String>>, th: Theme = FALLBACK): ByteArray {
        val parts = ArrayList<Pair<String, String>>()
        val n = slides.size.coerceAtLeast(1)

        val ct = StringBuilder(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        for (i in 1..n) ct.append("<Override PartName=\"/ppt/slides/slide$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        ct.append("</Types>")
        parts.add("[Content_Types].xml" to ct.toString())

        parts.add("_rels/.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "</Relationships>"))

        // 16:9 canvas.
        val sldIds = StringBuilder()
        for (i in 1..n) sldIds.append("<p:sldId id=\"${255 + i}\" r:id=\"rId${i + 1}\"/>")
        parts.add("ppt/presentation.xml" to (XML + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>" + sldIds + "</p:sldIdLst>" +
            "<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"))

        val presRels = StringBuilder(XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (i in 1..n) presRels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$i.xml\"/>")
        presRels.append("</Relationships>")
        parts.add("ppt/_rels/presentation.xml.rels" to presRels.toString())

        parts.add("ppt/theme/theme1.xml" to theme(th))
        parts.add("ppt/slideMasters/slideMaster1.xml" to master())
        parts.add("ppt/slideMasters/_rels/slideMaster1.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
            "</Relationships>"))
        parts.add("ppt/slideLayouts/slideLayout1.xml" to layout())
        parts.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>"))

        slides.forEachIndexed { i, (t, b) ->
            parts.add("ppt/slides/slide${i + 1}.xml" to slideXml(t, b, i + 1, th))
            parts.add("ppt/slides/_rels/slide${i + 1}.xml.rels" to (XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "</Relationships>"))
        }
        return zip(parts)
    }

    /** One slide: dark canvas, accent bar, title, bullet/body text, slide number. */
    private fun slideXml(title: String, body: String, num: Int, th: Theme): String {
        val lines = body.replace("\r", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val paras = if (lines.isEmpty()) "<a:p/>" else lines.joinToString("") { l ->
            val isB = l.startsWith("-") || l.startsWith("*") || l.startsWith("•")
            val txt = esc(if (isB) l.drop(1).trim() else l)
            "<a:p><a:pPr marL=\"${if (isB) 285750 else 0}\" indent=\"${if (isB) -285750 else 0}\">" +
                (if (isB) "<a:buChar char=\"•\"/>" else "<a:buNone/>") +
                "</a:pPr><a:r><a:rPr lang=\"en-US\" sz=\"${th.bodySize * 100}\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"${th.deckInk}\"/></a:solidFill>" +
                "<a:latin typeface=\"${th.font}\"/></a:rPr><a:t>$txt</a:t></a:r></a:p>"
        }
        return XML + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld>" +
            "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${th.deckBg}\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            // title
            shape(2, "Title", 838200, 736600, 10515600, 1200000,
                "<a:p><a:pPr><a:buNone/></a:pPr><a:r><a:rPr lang=\"en-US\" sz=\"${th.titleSize * 100}\" b=\"1\" dirty=\"0\">" +
                "<a:solidFill><a:srgbClr val=\"${th.deckInk}\"/></a:solidFill><a:latin typeface=\"${th.font}\"/></a:rPr>" +
                "<a:t>${esc(title)}</a:t></a:r></a:p>") +
            // accent bar
            "<p:sp><p:nvSpPr><p:cNvPr id=\"5\" name=\"Accent\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"838200\" y=\"2020000\"/><a:ext cx=\"900000\" cy=\"50000\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"${th.accent}\"/></a:solidFill>" +
            "<a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>" +
            // body
            shape(3, "Body", 838200, 2350000, 10515600, 3600000, paras) +
            // slide number
            shape(4, "Num", 11200000, 6200000, 500000, 300000,
                "<a:p><a:pPr algn=\"r\"><a:buNone/></a:pPr><a:r><a:rPr lang=\"en-US\" sz=\"1000\" dirty=\"0\">" +
                "<a:solidFill><a:srgbClr val=\"${th.muted}\"/></a:solidFill></a:rPr><a:t>$num</a:t></a:r></a:p>") +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private fun shape(id: Int, name: String, x: Long, y: Long, cx: Long, cy: Long, text: String): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$text</p:txBody></p:sp>"

    private fun master(): String = XML +
        "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree>" +
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
        "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" " +
        "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>"

    private fun layout(): String = XML +
        "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>" +
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun theme(th: Theme): String = XML +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"SlyOS\"><a:themeElements>" +
        "<a:clrScheme name=\"SlyOS\"><a:dk1><a:srgbClr val=\"${th.ink}\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"${th.deckBg}\"/></a:dk2><a:lt2><a:srgbClr val=\"${th.deckInk}\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"${th.accent}\"/></a:accent1><a:accent2><a:srgbClr val=\"${th.accent}\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"${th.muted}\"/></a:accent3><a:accent4><a:srgbClr val=\"${th.muted}\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"${th.muted}\"/></a:accent5><a:accent6><a:srgbClr val=\"${th.muted}\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"${th.accent}\"/></a:hlink><a:folHlink><a:srgbClr val=\"${th.muted}\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"SlyOS\"><a:majorFont><a:latin typeface=\"${th.font}\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"${th.font}\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"SlyOS\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "<a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "<a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>" +
        "</a:fmtScheme></a:themeElements></a:theme>"
}
