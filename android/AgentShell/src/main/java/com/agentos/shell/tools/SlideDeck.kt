package com.agentos.shell.tools

/**
 * Slides, laid out rather than described.
 *
 * A deck asked for as a PDF used to go through the same route as every other document: hand the
 * content to a model, ask it for HTML, print whatever comes back. For a one-pager that is fine — the
 * shape is a column of text and it is hard to get wrong. For a deck it is a coin flip. The model
 * decides the grid, the type scale and the colour every single time, so the same brief produces a
 * beautiful deck and an embarrassing one on consecutive taps, and neither can be predicted before
 * the PDF is open.
 *
 * The layout is fixed here instead. The model writes the WORDS — which is the part it is good at and
 * the part that should differ every time — and the geometry, the type scale, the rhythm and the
 * numbering are the same in every deck this ever produces. Only the palette moves, and it is derived
 * from the subject rather than invented per render.
 *
 * The rules the layout keeps to, which are the difference between slides and a bulleted document:
 *
 *  - **One idea per page, at a size you could read from across a room.** 40pt titles, 20pt bullets.
 *    A deck that fits eight bullets on a slide is a document someone has projected.
 *  - **A cover that does not look like a slide.** Full-bleed, oversized, no bullet list.
 *  - **Air.** Wide margins and generous leading; crowding is what makes a deck look homemade.
 *  - **Never a half-empty page with a title stranded at the top** — content sits on a baseline,
 *    vertically centred in the body area, so a three-bullet slide is composed rather than abandoned.
 */
object SlideDeck {

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Markdown, rendered rather than printed.
     *
     * The writer emits `**Own the edge** before Apple/Google notice` and this escaped the HTML and
     * set the asterisks. A finished deck came out with `**Mission**` as a heading and `**4-bit
     * quantization**` in a bullet — visible on every page, in the one artefact that gets put in
     * front of investors. The same defect was found and fixed in email drafts days earlier; slides
     * were never checked for it, which is exactly the kind of thing worth checking everywhere the
     * moment it is found anywhere.
     *
     * Emphasis is rendered where it is meaningful and dropped where it is not: a whole slide title
     * wrapped in asterisks is the model being emphatic about a heading that is already the largest
     * type on the page.
     */
    private fun inline(s: String): String = esc(s)
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "<em>$1</em>")
        // Anything left is an unpaired asterisk the writer opened and never closed — the real deck
        // ended its cover subtitle "…outmaneuver Apple & Android*". Only asterisks at an edge or
        // against a space are dropped, so "4*3" and "a*b" survive as the arithmetic they are.
        .replace("**", "")
        .replace(Regex("^\\*+|\\*+$"), "")
        .replace(Regex("\\*+(?=\\s)|(?<=\\s)\\*+"), "")

    /** A title needs no emphasis — it is already the biggest thing on the slide. */
    private fun plainTitle(s: String): String = esc(
        s.replace("**", "").replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
            .removePrefix("#").trim())

    /** One slide's title and its bullets, from the `===`-separated form the writer produces. */
    private fun parse(content: String): List<Pair<String, List<String>>> =
        content.split(Regex("(?m)^===+\\s*$"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val title = lines.firstOrNull().orEmpty().removePrefix("#").trim()
                val bullets = lines.drop(1)
                    .map { it.removePrefix("-").removePrefix("•").removePrefix("*").trim() }
                    .filter { it.isNotEmpty() }
                title to bullets
            }
            .filter { it.first.isNotEmpty() || it.second.isNotEmpty() }

    /**
     * The whole deck as one printable HTML document, one A4-landscape page per slide.
     *
     * Sizes are in mm against the real page rather than in px against a viewport, because the output
     * is a 300dpi print and a px-sized layout comes back either microscopic or clipped.
     */
    fun html(title: String, content: String, th: Ooxml.Theme, footer: String = ""): String {
        val slides = parse(content)
        if (slides.isEmpty()) return ""
        val accent = "#" + th.accent.trimStart('#')
        val bg = "#" + th.deckBg.trimStart('#')
        val ink = "#" + th.deckInk.trimStart('#')

        val body = StringBuilder()
        slides.forEachIndexed { i, (st, bullets) ->
            if (i == 0) {
                // The cover. Deliberately unlike every page after it: no rule, no number, no list —
                // a deck whose first slide is just another bulleted slide reads as a memo.
                body.append("<section class=\"pg cover\">")
                    .append("<div class=\"cov-mark\"></div>")
                    .append("<h1>").append(plainTitle(st.ifBlank { title })).append("</h1>")
                if (bullets.isNotEmpty())
                    body.append("<p class=\"sub\">").append(inline(bullets.first())).append("</p>")
                if (footer.isNotBlank())
                    body.append("<p class=\"cov-foot\">").append(esc(footer)).append("</p>")
                body.append("</section>")
            } else {
                body.append("<section class=\"pg\">")
                    .append("<div class=\"rule\"></div>")
                    .append("<h2>").append(plainTitle(st)).append("</h2>")
                    .append("<ul>")
                bullets.take(6).forEach { b -> body.append("<li>").append(inline(b)).append("</li>") }
                body.append("</ul>")
                    .append("<div class=\"num\">").append(i).append("</div>")
                    .append("</section>")
            }
        }

        return """<!doctype html><html><head><meta charset="utf-8">
<style>
  @page { size: A4 landscape; margin: 0; }
  * { margin:0; padding:0; box-sizing:border-box; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  body { font-family: -apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif;
         background:$bg; color:$ink; }
  .pg { position:relative; width:297mm; height:209.5mm; padding:26mm 30mm 22mm 30mm;
        background:$bg; page-break-after:always; break-after:page;
        display:flex; flex-direction:column; justify-content:center; overflow:hidden; }
  .pg:last-child { page-break-after:auto; break-after:auto; }

  /* Cover */
  .cover { justify-content:flex-end; padding-bottom:34mm; }
  .cover h1 { font-size:58pt; line-height:1.03; font-weight:700; letter-spacing:-0.028em;
              max-width:225mm; }
  .cov-mark { position:absolute; top:0; left:0; width:14mm; height:100%; background:$accent; }
  .sub { margin-top:9mm; font-size:19pt; line-height:1.34; color:$ink; opacity:.62;
         max-width:185mm; font-weight:400; }
  .cov-foot { position:absolute; bottom:16mm; left:30mm; font-size:11pt; opacity:.4;
              letter-spacing:.03em; }

  /* Content slides */
  .rule { position:absolute; top:26mm; left:30mm; width:19mm; height:1.6mm; background:$accent;
          border-radius:1mm; }
  h2 { font-size:38pt; line-height:1.1; font-weight:700; letter-spacing:-0.022em;
       margin-top:7mm; margin-bottom:11mm; max-width:210mm; }
  ul { list-style:none; max-width:220mm; }
  strong { font-weight:650; }
  li { font-size:20pt; line-height:1.45; font-weight:400; opacity:.9;
       padding-left:11mm; margin-bottom:6.5mm; position:relative; }
  /* A dot rather than a glyph — a real bullet character sits on the wrong baseline at this size. */
  li:before { content:""; position:absolute; left:0; top:3.6mm; width:3.4mm; height:3.4mm;
              border-radius:50%; background:$accent; }
  .num { position:absolute; bottom:15mm; right:30mm; font-size:11pt; opacity:.33;
         font-variant-numeric:tabular-nums; }
</style></head><body>$body</body></html>"""
    }
}
