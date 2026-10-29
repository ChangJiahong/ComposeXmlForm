package cn.changjiahong.composexmlform

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform