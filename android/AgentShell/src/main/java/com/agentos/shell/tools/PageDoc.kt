package com.agentos.shell.tools

/**
 * Documents, laid out rather than described — the counterpart to [SlideDeck].
 *
 * Decks were moved off the model-designed HTML path because a deck's layout is a grid, a type scale
 * and a rhythm, and asking for those afresh on every render means the same brief produces a
 * beautiful deck and an embarrassing one on consecutive taps. Every word of that applies to a
 * one-pager, a memo and a report, which is most of what this app produces — and they were left on
 * the improvised path.
 *
 * The evidence that it was improvising badly is in the prompt that drove it, which has accumulated
 * warnings written after each failure: a document that invented a company called Northbeam and gave
 * it positioning; a children's science explainer stamped with the owner's employer in the footer of
 * all fourteen pages. Those are content failures the layout prompt was asked to police, because
 * layout and content were being generated in the same breath. Separating them means the content
 * generator can be given one job and the layout cannot drift at all.
 *
 * What the layout is:
 *
 *  - **A header band that states what this is and who it is from**, once, at the top — not repeated
 *    branding on every page, which is what produced the stamped-employer bug.
 *  - **A measure that is readable.** Roughly 90 characters, because full-width A4 body text is the
 *    single most common reason a generated document looks like a generated document.
 *  - **Hierarchy with air.** Section rules, real spacing above headings, and a pull-quote style that
 *    is distinct from body text rather than merely indented.
 *  - **Page furniture that behaves.** Headings do not strand themselves at the foot of a page and
 *    list items do not split across one.
 */
object PageDoc {

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Light inline emphasis — and the asterisks nobody closed.
     *
     * The paired forms were handled and the UNPAIRED ones were not, so a writer that opened `**` and
     * never closed it put raw asterisks on a finished page. Exactly the same defect was found and
     * fixed in slides; documents were never checked for it, which is the argument for checking
     * everywhere the moment something is found anywhere.
     */
    private fun inline(s: String): String = esc(s)
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "<em>$1</em>")
        // Only asterisks at an edge or against a space go, so "4*3" and "a*b" survive as arithmetic.
        .replace("**", "")
        .replace(Regex("^\\*+|\\*+$"), "")
        .replace(Regex("\\*+(?=\\s)|(?<=\\s)\\*+"), "")

    /** A heading is already the largest type in its section; emphasis inside one is noise. */
    private fun plainHead(s: String): String = esc(
        s.replace("**", "").replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1").trim())

    /**
     * The document as one printable A4 page-stream.
     *
     * Input is the light markdown the content generator already produces: `# ` and `## ` headings,
     * `- ` bullets, `> ` pull-quotes, blank-line-separated paragraphs. Anything unrecognised is a
     * paragraph, so a stray line can never vanish — losing a sentence silently is far worse than
     * setting it plainly.
     */
    fun html(title: String, content: String, th: Ooxml.Theme, byline: String = ""): String {
        val accent = "#" + th.accent.trimStart('#')
        val ink = "#" + th.ink.trimStart('#')
        val muted = "#" + th.muted.trimStart('#')

        val body = StringBuilder()
        var inList = false
        var inOrdered = false
        fun closeList() {
            if (inList) { body.append("</ul>"); inList = false }
            if (inOrdered) { body.append("</ol>"); inOrdered = false }
        }

        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> closeList()
                line.startsWith("# ") -> {
                    closeList(); body.append("<h2>").append(plainHead(line.removePrefix("# "))).append("</h2>")
                }
                line.startsWith("## ") -> {
                    closeList(); body.append("<h3>").append(plainHead(line.removePrefix("## "))).append("</h3>")
                }
                line.startsWith("> ") -> {
                    closeList()
                    body.append("<blockquote>").append(inline(line.removePrefix("> "))).append("</blockquote>")
                }
                line.startsWith("- ") || line.startsWith("• ") -> {
                    if (inOrdered) { body.append("</ol>"); inOrdered = false }
                    if (!inList) { body.append("<ul>"); inList = true }
                    body.append("<li>").append(inline(line.drop(2))).append("</li>")
                }
                // A NUMBERED LIST, WHICH AN AGENDA ALWAYS IS.
                //
                // "1. Market positioning (20 min)" fell through to the paragraph branch — no indent,
                // no hanging number, no grouping, the bare digits set as body text. The single most
                // common shape this renderer produces is an agenda, and it was the one shape it did
                // not know. Numbering comes from the list so the items renumber themselves after an
                // edit, rather than the writer's digits going stale the moment one is deleted.
                Regex("^\\d{1,2}[.)]\\s").containsMatchIn(line) -> {
                    if (inList) { body.append("</ul>"); inList = false }
                    if (!inOrdered) { body.append("<ol>"); inOrdered = true }
                    body.append("<li>")
                        .append(inline(line.replaceFirst(Regex("^\\d{1,2}[.)]\\s+"), "")))
                        .append("</li>")
                }
                else -> { closeList(); body.append("<p>").append(inline(line)).append("</p>") }
            }
        }
        closeList()
        if (body.isEmpty()) return ""

        val head = StringBuilder("<header><div class=\"bar\"></div><h1>")
            .append(esc(title)).append("</h1>")
        if (byline.isNotBlank()) head.append("<p class=\"by\">").append(esc(byline)).append("</p>")
        head.append("</header>")

        return """<!doctype html><html><head><meta charset="utf-8">
<style>
  @page { size: A4 portrait; margin: 17mm 18mm 16mm 18mm; }
  * { margin:0; padding:0; box-sizing:border-box; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  body { font-family: -apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif;
         color:$ink; font-size:10.5pt; line-height:1.62; }
  /* ~90 characters. Full-width A4 body text is the thing that makes a document look generated. */
  header, p, ul, ol, blockquote, h2, h3 { max-width:158mm; }

  header { margin-bottom:9mm; }
  .bar { width:17mm; height:1.7mm; background:$accent; border-radius:1mm; margin-bottom:5mm; }
  h1 { font-size:23pt; line-height:1.16; font-weight:700; letter-spacing:-0.02em; }
  .by { margin-top:3mm; font-size:9pt; color:$muted; letter-spacing:.02em; }

  h2 { font-size:13.5pt; font-weight:700; letter-spacing:-0.01em; margin:8mm 0 2.5mm;
       padding-top:3mm; border-top:0.35mm solid rgba(0,0,0,.11); break-after:avoid; page-break-after:avoid; }
  h3 { font-size:11pt; font-weight:600; margin:5.5mm 0 1.5mm; color:$ink;
       break-after:avoid; page-break-after:avoid; }
  p { margin-bottom:3.2mm; }
  ul, ol { margin:0 0 3.4mm 0; list-style:none; }
  /* Hanging numbers on their own tabular column, so multi-line items align under their text
     rather than under the digit — the difference between an agenda and a numbered paragraph. */
  ol { counter-reset:item; }
  ol > li { padding-left:9mm; }
  ol > li:before { counter-increment:item; content:counter(item) "."; position:absolute; left:0;
                   top:0; width:7mm; text-align:left; color:$accent; font-weight:650;
                   font-variant-numeric:tabular-nums; background:none; border-radius:0;
                   height:auto; }
  li { position:relative; padding-left:6.5mm; margin-bottom:1.8mm;
       break-inside:avoid; page-break-inside:avoid; }
  li:before { content:""; position:absolute; left:0.6mm; top:2.1mm; width:1.9mm; height:1.9mm;
              border-radius:50%; background:$accent; }
  blockquote { margin:5mm 0; padding:1mm 0 1mm 6mm; border-left:1mm solid $accent;
               font-size:11.5pt; line-height:1.5; color:$ink; break-inside:avoid; }
  strong { font-weight:650; }
</style></head><body>$head$body</body></html>"""
    }
}
