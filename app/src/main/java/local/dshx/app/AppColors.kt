package local.dshx.app

/** 原生小窗与 Compose 主界面共用的语义色，避免两套近似主题逐渐偏离。 */
internal class AppColors(dark: Boolean) {
    val background = if (dark) 0xFF0E0E0E.toInt() else 0xFFF7F7F5.toInt()
    val surface = if (dark) 0xFF181818.toInt() else 0xFFFCFCFB.toInt()
    val text = if (dark) 0xFFECECEC.toInt() else 0xFF202220.toInt()
    val muted = if (dark) 0xFFA6A6A6.toInt() else 0xFF6D726D.toInt()
    val border = if (dark) 0xFF333333.toInt() else 0xFFE4E5E2.toInt()
    val primary = if (dark) 0xFF78A7EF.toInt() else 0xFF2F6CC4.toInt()
    val input = if (dark) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
    val selected = if (dark) 0xFF292929.toInt() else 0xFFF0F1EF.toInt()
    val danger = if (dark) 0xFFE3848A.toInt() else 0xFFB33D48.toInt()
    val success = if (dark) 0xFF63B58B.toInt() else 0xFF23845F.toInt()
    val noteBackground = if (dark) 0xFF34342E.toInt() else 0xFFEAE6DC.toInt()
    val noteForeground = if (dark) 0xFFD4CBB7.toInt() else 0xFF655F51.toInt()
    val screenBackground = if (dark) 0xFF283440.toInt() else 0xFFE5EDF5.toInt()
    val screenForeground = if (dark) 0xFFA1BDD9.toInt() else 0xFF4B6F92.toInt()
}
