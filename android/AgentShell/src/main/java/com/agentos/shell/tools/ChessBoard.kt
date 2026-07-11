package com.agentos.shell.tools

import com.agentos.shell.InteractionLogService.ScreenNode

/**
 * Reads a live chess position off the screen via the accessibility tree. Chess apps (chess.com, and most
 * others) label every square — "White Knight on f3", "e4, empty" — so we can reconstruct the exact board as
 * a FEN string WITHOUT any vision guessing, and also remember each square's on-screen pixel centre so a hint
 * arrow can be drawn on the board. Tuned for chess.com's phrasing; other apps may need small tweaks.
 */
object ChessBoard {
    data class Board(val fen: String, val coords: Map<String, Pair<Int, Int>>, val userColor: Char)

    private val LETTER = mapOf("king" to 'k', "queen" to 'q', "rook" to 'r', "bishop" to 'b', "knight" to 'n', "pawn" to 'p')
    private val PIECE = Regex("(?i)(white|black)\\s+(king|queen|rook|bishop|knight|pawn)\\s+(?:on\\s+)?([a-h][1-8])")
    private val EMPTY = Regex("(?i)\\b([a-h][1-8])\\b[,\\s]+empty")

    /** Parse the current board. [side] = 'w' or 'b' (whose move it is). Returns null if no board is on screen. */
    fun parse(nodes: List<ScreenNode>, side: Char): Board? {
        val grid = Array(8) { arrayOfNulls<Char>(8) }   // grid[0] = rank 8 … grid[7] = rank 1
        val coords = HashMap<String, Pair<Int, Int>>()
        var pieces = 0
        var whiteCyN = 0; var whiteCySum = 0L; var blackCyN = 0; var blackCySum = 0L
        for (n in nodes) {
            val t = n.text
            val cx = n.bounds.centerX(); val cy = n.bounds.centerY()
            PIECE.find(t)?.let { m ->
                val color = m.groupValues[1].lowercase()
                val piece = m.groupValues[2].lowercase()
                val sq = m.groupValues[3].lowercase()
                var ch = LETTER[piece] ?: return@let
                if (color == "white") { ch = ch.uppercaseChar(); whiteCyN++; whiteCySum += cy } else { blackCyN++; blackCySum += cy }
                val file = sq[0] - 'a'; val rank = sq[1] - '1'
                grid[7 - rank][file] = ch
                coords[sq] = cx to cy; pieces++
            }
            EMPTY.find(t)?.let { m -> coords[m.groupValues[1].lowercase()] = cx to cy }
        }
        if (pieces < 2) return null   // not a chessboard we can read
        // Orientation: your own pieces sit at the BOTTOM (larger screen y). Whichever colour's pieces are
        // lower on screen is the colour YOU are playing.
        val whiteAvg = if (whiteCyN > 0) whiteCySum.toDouble() / whiteCyN else 0.0
        val blackAvg = if (blackCyN > 0) blackCySum.toDouble() / blackCyN else 0.0
        val userColor = if (whiteAvg >= blackAvg) 'w' else 'b'

        val sb = StringBuilder()
        for (rank in 0..7) {
            var empty = 0
            for (file in 0..7) {
                val c = grid[rank][file]
                if (c == null) empty++ else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(c) }
            }
            if (empty > 0) sb.append(empty)
            if (rank < 7) sb.append('/')
        }
        // Real castling rights (a king/rook still on its home square) — hardcoding "KQkq" produces an
        // ILLEGAL FEN once the king has moved, which the engine rejects. Only claim what's actually possible.
        val cr = StringBuilder()
        if (grid[7][4] == 'K') { if (grid[7][7] == 'R') cr.append('K'); if (grid[7][0] == 'R') cr.append('Q') }
        if (grid[0][4] == 'k') { if (grid[0][7] == 'r') cr.append('k'); if (grid[0][0] == 'r') cr.append('q') }
        val castle = if (cr.isEmpty()) "-" else cr.toString()
        // side == 'a' means AUTO → assume it's the user's move (that's when a hint is useful).
        val turn = if (side == 'w' || side == 'b') side else userColor
        val fen = "$sb $turn $castle - 0 1"
        return Board(fen, coords, userColor)
    }
}
