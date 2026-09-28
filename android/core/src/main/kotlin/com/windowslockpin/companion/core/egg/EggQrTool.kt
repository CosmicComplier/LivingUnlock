package com.windowslockpin.companion.core.egg

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.file.Files
import java.nio.file.Path

object EggQrTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args[1].isNotBlank() && args[2].isNotBlank()) {
            "Use :core:generateEgg -PeggMode=A7|C4|P9 -PeggText=<utf8-file> -PeggOutput=<output.svg>"
        }
        val input = Path.of(args[1]); require(Files.size(input) <= EggCodec.MAX_TEXT_BYTES)
        val password = System.getenv("LIVINGUNLOCK_EGG_PASSWORD")?.toCharArray()
        val raw = try { EggCodec.encrypt(args[0], Files.readString(input).removePrefix("\uFEFF"), password) }
            finally { password?.fill('\u0000') }
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val svg = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\"")
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x,y]) append("M$x,$y h1v1h-1z ")
            append("\"/></svg>")
        }
        val output = Path.of(args[2]).toAbsolutePath()
        require(!Files.exists(output)) { "Output already exists" }
        Files.createDirectories(output.parent); Files.writeString(output, svg)
        println("Created QR: $output")
    }
}
