package org.soulstone.overwatch.data.targets

/**
 * Every MA-L block registered to Espressif Inc., the ESP32/ESP8266 maker, from the
 * IEEE CSV of 2026-11-01 (374 blocks). Espressif's chips are in countless
 * gadgets, so membership is never evidence on its own; it is used to tell an ESP32
 * pentest tool from the real thing when both broadcast the same name
 * ([HackerTargets.isMarauderFlockDecoy]). An ESP32's soft-AP address is its base
 * address plus one, inside the same block.
 */
object EspressifOuis {

    private val BLOCKS: Set<String> = (
        "004b12 007007 00fb4a 048308 04b247 083a8d 083af2 089272 08a6f7 08ad0a 08b61f 08d1f9 " +
        "08f9e0 0c4ea0 0c8b95 0cb815 0cdc7e 10003b 10061c 1020ba 1051db 10521c 1091a8 1097bd " +
        "10a85b 10b41d 10bda3 140808 142b2f 14335c 146393 14c19f 188b0e 18fe34 1c2904 1c6465 " +
        "1c6920 1c8b84 1c8f57 1c9dc2 1cc3ab 1cdbd4 1ce4cb 202565 2043a8 20500d 206ef1 209ba9 " +
        "20d5c2 20e5a4 20e7c8 240ac4 244cab 24587c 2462ab 246f28 24a160 24b2de 24d7eb 24dcc3 " +
        "24ec4a 2805a5 28372f 28562f 288485 2c3ae8 2cbcbb 2cf432 3030f9 3076f5 308398 30aea4 " +
        "30c6f7 30c922 30eda0 345f45 348518 34865d 349454 34987a 34ab95 34b472 34b7da 34c934 " +
        "34cdb0 38182b 383e51 3844be 3c0d0d 3c0f02 3c6105 3c71bf 3c8427 3c8a1f 3cdc75 3ce255 " +
        "3ce90e 4022d8 402bd6 404cca 409151 40f520 441793 441bf6 441d64 447b30 44b176 44bd8d " +
        "4827e2 4831b7 483fda 485519 489d31 48aff3 48ca43 48e729 48f6ee 4c11ae 4c7525 4c8421 " +
        "4cc382 4cebd6 500291 50787d 5416a5 542b88 543204 5443b2 545aa6 549dea 582abd 582bd3 " +
        "588c81 58b145 58bf25 58cf79 58e6c5 5c013b 5ccf7f 600194 6055f9 648914 64b708 64e833 " +
        "680947 6825dd 686725 689dd2 68b6b3 68c63a 68ee8f 68fe71 6c2fa0 6c3dd8 6cb456 6cc4af " +
        "6cc840 6ce210 70039f 70041d 704bca 70af09 70b8f6 744dbd 748927 781c3c 782184 78421c " +
        "78e36d 78ee4c 7c0c5f 7c2c67 7c4fad 7c7398 7c87ce 7c9ebd 7cd544 7cdfa1 7ce8b1 80456b " +
        "8053e0 80646f 806599 807d3a 80b54e 80f1b2 80f3da 840d8e 841fe8 84c7bb 84cca8 84f3eb " +
        "84f703 84fce6 8813bf 8856a6 885721 88f155 8c4598 8c4b14 8c4f00 8c8c29 8c94df 8caab5 " +
        "8cbfea 8cce4e 8cfd49 901506 90380c 90649b 907069 9097d5 90b339 90da72 90e5b1 943cc6 " +
        "9451dc 9454c5 94a990 94b555 94b97e 94e686 983dae 9888e0 98a316 98c377 98cdac 98f4ab " +
        "9c139e 9c96d5 9c9c1f 9c9e6e 9ccc01 a020a6 a06025 a0764e a085e3 a0a3b3 a0b765 a0dd6c " +
        "a0f262 a47b9d a4cb8f a4cf12 a4e57c a4e972 a4f00f a8032a a842e3 a84674 a848fa a8fd07 " +
        "ac0bfb ac1518 ac276e ac67b2 aca26a aca704 acd074 acebe6 b03fd3 b08184 b0a604 b0a732 " +
        "b0b21c b0cbd8 b43a45 b48a0a b4a64a b4bfe9 b4e62d b4feb5 b81f3f b87b4d b8bb11 b8d61a " +
        "b8f009 b8f862 bc1926 bcddc2 bcff4d c049ef c04e30 c05d89 c0cdd6 c44f33 c45bbe c49e7e " +
        "c4d8d5 c4dd57 c4dee2 c82b96 c82e18 c88541 c88a7b c8c9a3 c8da29 c8ea71 c8ed8b c8f09e " +
        "cc50e3 cc68c7 cc7b5c cc7e1f cc8da2 ccba97 ccdba7 cce732 d09aaf d0cf13 d0ef76 d40592 " +
        "d48afc d48c49 d4d4da d4e9f4 d4f98d d8132a d83bda d885ac d8a01d d8bc38 d8bfc0 d8f15b " +
        "dc0675 dc0a69 dc1ed5 dc4f22 dc5475 dc55b1 dcb4d9 dcda0c e05a1b e072a1 e08cfe e09806 " +
        "e0e2e6 e465b8 e4b063 e4b323 e80690 e831cd e83dc1 e868e7 e86bea e89f6d e8db84 e8edbd " +
        "e8f60a ec6260 ec64c9 ec94cb ecc9ff ecd61b ecda3b ece334 ecfabc f008d1 f0161d f024f9 " +
        "f09e9e f0f5bd f412fa f42dc9 f4650b f4cfa2 f80c4e f843ee f85b1b f8b3b7 fc012c fcb467 " +
        "fce8c0 fcf5c4"
    ).trim().split(' ').toSet()

    fun contains(mac: String?): Boolean {
        if (mac == null) return false
        val hex = mac.lowercase().replace(":", "")
        return hex.length >= 6 && hex.substring(0, 6) in BLOCKS
    }
}
