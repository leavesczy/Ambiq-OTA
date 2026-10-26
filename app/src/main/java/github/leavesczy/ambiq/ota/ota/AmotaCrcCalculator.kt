package github.leavesczy.ambiq.ota.ota

object AmotaCrcCalculator {

    private fun h(value: Long): Int {
        return value.toInt()
    }

    private val CRC32_TABLE = intArrayOf(
        h(value = 0x00000000), h(value = 0x77073096), h(value = 0xEE0E612C), h(value = 0x990951BA),
        h(value = 0x076DC419), h(value = 0x706AF48F), h(value = 0xE963A535), h(value = 0x9E6495A3),
        h(value = 0x0EDB8832), h(value = 0x79DCB8A4), h(value = 0xE0D5E91E), h(value = 0x97D2D988),
        h(value = 0x09B64C2B), h(value = 0x7EB17CBD), h(value = 0xE7B82D07), h(value = 0x90BF1D91),
        h(value = 0x1DB71064), h(value = 0x6AB020F2), h(value = 0xF3B97148), h(value = 0x84BE41DE),
        h(value = 0x1ADAD47D), h(value = 0x6DDDE4EB), h(value = 0xF4D4B551), h(value = 0x83D385C7),
        h(value = 0x136C9856), h(value = 0x646BA8C0), h(value = 0xFD62F97A), h(value = 0x8A65C9EC),
        h(value = 0x14015C4F), h(value = 0x63066CD9), h(value = 0xFA0F3D63), h(value = 0x8D080DF5),
        h(value = 0x3B6E20C8), h(value = 0x4C69105E), h(value = 0xD56041E4), h(value = 0xA2677172),
        h(value = 0x3C03E4D1), h(value = 0x4B04D447), h(value = 0xD20D85FD), h(value = 0xA50AB56B),
        h(value = 0x35B5A8FA), h(value = 0x42B2986C), h(value = 0xDBBBC9D6), h(value = 0xACBCF940),
        h(value = 0x32D86CE3), h(value = 0x45DF5C75), h(value = 0xDCD60DCF), h(value = 0xABD13D59),
        h(value = 0x26D930AC), h(value = 0x51DE003A), h(value = 0xC8D75180), h(value = 0xBFD06116),
        h(value = 0x21B4F4B5), h(value = 0x56B3C423), h(value = 0xCFBA9599), h(value = 0xB8BDA50F),
        h(value = 0x2802B89E), h(value = 0x5F058808), h(value = 0xC60CD9B2), h(value = 0xB10BE924),
        h(value = 0x2F6F7C87), h(value = 0x58684C11), h(value = 0xC1611DAB), h(value = 0xB6662D3D),
        h(value = 0x76DC4190), h(value = 0x01DB7106), h(value = 0x98D220BC), h(value = 0xEFD5102A),
        h(value = 0x71B18589), h(value = 0x06B6B51F), h(value = 0x9FBFE4A5), h(value = 0xE8B8D433),
        h(value = 0x7807C9A2), h(value = 0x0F00F934), h(value = 0x9609A88E), h(value = 0xE10E9818),
        h(value = 0x7F6A0DBB), h(value = 0x086D3D2D), h(value = 0x91646C97), h(value = 0xE6635C01),
        h(value = 0x6B6B51F4), h(value = 0x1C6C6162), h(value = 0x856530D8), h(value = 0xF262004E),
        h(value = 0x6C0695ED), h(value = 0x1B01A57B), h(value = 0x8208F4C1), h(value = 0xF50FC457),
        h(value = 0x65B0D9C6), h(value = 0x12B7E950), h(value = 0x8BBEB8EA), h(value = 0xFCB9887C),
        h(value = 0x62DD1DDF), h(value = 0x15DA2D49), h(value = 0x8CD37CF3), h(value = 0xFBD44C65),
        h(value = 0x4DB26158), h(value = 0x3AB551CE), h(value = 0xA3BC0074), h(value = 0xD4BB30E2),
        h(value = 0x4ADFA541), h(value = 0x3DD895D7), h(value = 0xA4D1C46D), h(value = 0xD3D6F4FB),
        h(value = 0x4369E96A), h(value = 0x346ED9FC), h(value = 0xAD678846), h(value = 0xDA60B8D0),
        h(value = 0x44042D73), h(value = 0x33031DE5), h(value = 0xAA0A4C5F), h(value = 0xDD0D7CC9),
        h(value = 0x5005713C), h(value = 0x270241AA), h(value = 0xBE0B1010), h(value = 0xC90C2086),
        h(value = 0x5768B525), h(value = 0x206F85B3), h(value = 0xB966D409), h(value = 0xCE61E49F),
        h(value = 0x5EDEF90E), h(value = 0x29D9C998), h(value = 0xB0D09822), h(value = 0xC7D7A8B4),
        h(value = 0x59B33D17), h(value = 0x2EB40D81), h(value = 0xB7BD5C3B), h(value = 0xC0BA6CAD),
        h(value = 0xEDB88320), h(value = 0x9ABFB3B6), h(value = 0x03B6E20C), h(value = 0x74B1D29A),
        h(value = 0xEAD54739), h(value = 0x9DD277AF), h(value = 0x04DB2615), h(value = 0x73DC1683),
        h(value = 0xE3630B12), h(value = 0x94643B84), h(value = 0x0D6D6A3E), h(value = 0x7A6A5AA8),
        h(value = 0xE40ECF0B), h(value = 0x9309FF9D), h(value = 0x0A00AE27), h(value = 0x7D079EB1),
        h(value = 0xF00F9344), h(value = 0x8708A3D2), h(value = 0x1E01F268), h(value = 0x6906C2FE),
        h(value = 0xF762575D), h(value = 0x806567CB), h(value = 0x196C3671), h(value = 0x6E6B06E7),
        h(value = 0xFED41B76), h(value = 0x89D32BE0), h(value = 0x10DA7A5A), h(value = 0x67DD4ACC),
        h(value = 0xF9B9DF6F), h(value = 0x8EBEEFF9), h(value = 0x17B7BE43), h(value = 0x60B08ED5),
        h(value = 0xD6D6A3E8), h(value = 0xA1D1937E), h(value = 0x38D8C2C4), h(value = 0x4FDFF252),
        h(value = 0xD1BB67F1), h(value = 0xA6BC5767), h(value = 0x3FB506DD), h(value = 0x48B2364B),
        h(value = 0xD80D2BDA), h(value = 0xAF0A1B4C), h(value = 0x36034AF6), h(value = 0x41047A60),
        h(value = 0xDF60EFC3), h(value = 0xA867DF55), h(value = 0x316E8EEF), h(value = 0x4669BE79),
        h(value = 0xCB61B38C), h(value = 0xBC66831A), h(value = 0x256FD2A0), h(value = 0x5268E236),
        h(value = 0xCC0C7795), h(value = 0xBB0B4703), h(value = 0x220216B9), h(value = 0x5505262F),
        h(value = 0xC5BA3BBE), h(value = 0xB2BD0B28), h(value = 0x2BB45A92), h(value = 0x5CB36A04),
        h(value = 0xC2D7FFA7), h(value = 0xB5D0CF31), h(value = 0x2CD99E8B), h(value = 0x5BDEAE1D),
        h(value = 0x9B64C2B0), h(value = 0xEC63F226), h(value = 0x756AA39C), h(value = 0x026D930A),
        h(value = 0x9C0906A9), h(value = 0xEB0E363F), h(value = 0x72076785), h(value = 0x05005713),
        h(value = 0x95BF4A82), h(value = 0xE2B87A14), h(value = 0x7BB12BAE), h(value = 0x0CB61B38),
        h(value = 0x92D28E9B), h(value = 0xE5D5BE0D), h(value = 0x7CDCEFB7), h(value = 0x0BDBDF21),
        h(value = 0x86D3D2D4), h(value = 0xF1D4E242), h(value = 0x68DDB3F8), h(value = 0x1FDA836E),
        h(value = 0x81BE16CD), h(value = 0xF6B9265B), h(value = 0x6FB077E1), h(value = 0x18B74777),
        h(value = 0x88085AE6), h(value = 0xFF0F6A70), h(value = 0x66063BCA), h(value = 0x11010B5C),
        h(value = 0x8F659EFF), h(value = 0xF862AE69), h(value = 0x616BFFD3), h(value = 0x166CCF45),
        h(value = 0xA00AE278), h(value = 0xD70DD2EE), h(value = 0x4E048354), h(value = 0x3903B3C2),
        h(value = 0xA7672661), h(value = 0xD06016F7), h(value = 0x4969474D), h(value = 0x3E6E77DB),
        h(value = 0xAED16A4A), h(value = 0xD9D65ADC), h(value = 0x40DF0B66), h(value = 0x37D83BF0),
        h(value = 0xA9BCAE53), h(value = 0xDEBB9EC5), h(value = 0x47B2CF7F), h(value = 0x30B5FFE9),
        h(value = 0xBDBDF21C), h(value = 0xCABAC28A), h(value = 0x53B39330), h(value = 0x24B4A3A6),
        h(value = 0xBAD03605), h(value = 0xCDD70693), h(value = 0x54DE5729), h(value = 0x23D967BF),
        h(value = 0xB3667A2E), h(value = 0xC4614AB8), h(value = 0x5D681B02), h(value = 0x2A6F2B94),
        h(value = 0xB40BBE37), h(value = 0xC30C8EA1), h(value = 0x5A05DF1B), h(value = 0x2D02EF8D)
    )

    fun calcCrc32(len: Int, buf: ByteArray): Int {
        var crc = -1
        for (i in 0 until len) {
            val a = buf[i].toInt() and 0xFF
            val b = crc and 0xFF
            crc = CRC32_TABLE[a xor b] xor (crc ushr 8)
        }
        return crc.inv()
    }

}
