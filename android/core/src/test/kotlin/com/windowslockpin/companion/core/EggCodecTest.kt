package com.windowslockpin.companion.core
import com.windowslockpin.companion.core.egg.EggCodec
import org.junit.Assert.*
import org.junit.Test
class EggCodecTest {
    @Test fun modesRoundTripAndTamperFail() {
        for (mode in listOf("A7", "C4", "P9")) {
            val password = "public-test-passphrase".toCharArray()
            val raw = EggCodec.encrypt(mode, "你好，LivingUnlock\n秘密在星光里。", password)
            assertEquals("你好，LivingUnlock\n秘密在星光里。", EggCodec.decrypt(raw, password))
            assertNotEquals(raw, EggCodec.encrypt(mode, "你好，LivingUnlock\n秘密在星光里。", password))
            val split = raw.split('.').toMutableList()
            split[5] = (if(split[5][0]=='A') "B" else "A") + split[5].drop(1)
            assertThrows(Exception::class.java) { EggCodec.decrypt(split.joinToString("."), password) }
            assertThrows(Exception::class.java) { EggCodec.decrypt(raw.replace(".K1.",".K9.").replace(".P0.",".P8."), password) }
        }
    }
    @Test fun passwordAndRoutingAreSeparate() {
        val raw=EggCodec.encrypt("P9","private test", "correct horse".toCharArray())
        assertThrows(Exception::class.java) { EggCodec.decrypt(raw,"wrong".toCharArray()) }
        assertFalse(EggCodec.isEgg("wslp://pair?v=1"))
        assertTrue(EggCodec.isEgg(raw))
        assertThrows(Exception::class.java) { EggCodec.parse("livingunlock:egg:1.ZZ.K1.-.abc.def") }
    }
}
