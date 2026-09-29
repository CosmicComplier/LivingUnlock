package com.windowslockpin.companion.core.egg

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.file.Files
import java.nio.file.Path

object EggQrTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4 && args[1].isNotBlank() && args[2].isNotBlank() && args[3].isNotBlank()) {
            "Use :core:generateEgg -PeggMode=A7|C4|P9 -PeggType=TEXT|IMAGE|IMAGE_URL|VIDEO_URL -PeggInput=<file> -PeggOutput=<output.svg>"
        }
        val input = Path.of(args[2])
        val password = System.getenv("LIVINGUNLOCK_EGG_PASSWORD")?.toCharArray()
        val payload = when (args[1].uppercase()) {
            "TEXT" -> EggCodec.Payload.Text(Files.readString(input).removePrefix("\uFEFF"))
            "IMAGE_URL" -> EggCodec.Payload.MediaUrl(EggCodec.MediaKind.IMAGE, Files.readString(input).trim())
            "VIDEO_URL" -> EggCodec.Payload.MediaUrl(EggCodec.MediaKind.VIDEO, Files.readString(input).trim())
            "IMAGE" -> readPreparedImage(input)
            else -> throw IllegalArgumentException("Unsupported egg content type")
        }
        val raw = try { EggCodec.encryptPayload(args[0], payload, password) }
            finally { password?.fill('\u0000') }
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val svg = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\"")
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x,y]) append("M$x,$y h1v1h-1z ")
            append("\"/></svg>")
        }
        val output = Path.of(args[3]).toAbsolutePath()
        require(!Files.exists(output)) { "Output already exists" }
        Files.createDirectories(output.parent); Files.writeString(output, svg)
        println("Created QR: $output")
    }

    private fun readPreparedImage(path: Path): EggCodec.Payload.Image {
        val bytes = Files.readAllBytes(path)
        require(bytes.size in 4..EggCodec.MAX_PAYLOAD_BYTES - 5) { "图片数据过大" }
        val mime = when {
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            ) -> "image/png"
            else -> throw IllegalArgumentException("预处理图片必须是 JPEG 或 PNG")
        }
        return EggCodec.Payload.Image(mime, bytes)
    }
}
