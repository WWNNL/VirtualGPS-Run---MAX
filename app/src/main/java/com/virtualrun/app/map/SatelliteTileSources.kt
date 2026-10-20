package com.virtualrun.app.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 高德卫星影像瓦片源 (style=6)。
 *
 * - 与 ChinaMapTileSource 同属高德、同一套 GCJ-02 加密坐标，所以
 *   CoordinateConverter（WGS-84 -> GCJ-02）无需改动，路线点与标记仍然对得上。
 * - 实测 z0-z18 返回 256x256 JPEG；z19 起返回约 4KB 的空白占位图，因此网络请求
 *   上限沿用 CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL = 18，再由 osmdroid 超采样放大到 z22。
 * - 必须走 webst0x 节点；webrd0x 节点带 style=6 会返回 404。
 *
 * 注意：高德同域的 style=7 曾被误当作「透明注记层」，实测（2026-10-19，逐像素解 PNG）
 * 它是不透明的浅色矢量路网图（palette PNG，无 tRNS，透明像素占比 0%），
 * 叠在卫星图上会把影像整块遮住。无 key 的栅格里没有可用的透明标注层，
 * 所以卫星模式只切底图，不做叠加。
 */
class SatelliteMapTileSource : OnlineTileSourceBase(
    "AMapSatellite",
    CHINA_MAP_MIN_ZOOM_LEVEL, CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL, 256, ".jpg",
    arrayOf(
        "https://webst01.is.autonavi.com/appmaptile?style=6",
        "https://webst02.is.autonavi.com/appmaptile?style=6",
        "https://webst03.is.autonavi.com/appmaptile?style=6",
        "https://webst04.is.autonavi.com/appmaptile?style=6"
    )
) {
    override fun getTileURLString(pTileIndex: Long): String {
        return "${baseUrl}&x=${MapTileIndex.getX(pTileIndex)}&y=${MapTileIndex.getY(pTileIndex)}&z=${MapTileIndex.getZoom(pTileIndex)}"
    }
}

/**
 * 高德卫星地名注记层（webst 节点 style=8）。
 *
 * 实测（2026-10-19，逐像素解 alpha）：透明底 PNG，约 77% 像素完全透明，
 * 只绘制道路线划、POI 图标和地名/楼栋号，可以直接叠在 [SatelliteMapTileSource] 之上，
 * 且两者同属高德 GCJ-02 瓦片网格，路名与影像里的真实道路对齐。
 *
 * 坑：webrd 节点的 style=8 是不透明的整幅路网图（底色铺满），
 * 拿来叠加会把卫星影像整块盖住，只能走 webst 节点。
 * style=7 也是不透明的（浅色路网底图），同样不能叠。
 */
class SatelliteLabelTileSource : OnlineTileSourceBase(
    "AMapSatelliteLabels",
    CHINA_MAP_MIN_ZOOM_LEVEL, CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL, 256, ".png",
    arrayOf(
        "https://webst01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webst02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webst03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webst04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8"
    )
) {
    override fun getTileURLString(pTileIndex: Long): String {
        return "${baseUrl}&x=${MapTileIndex.getX(pTileIndex)}&y=${MapTileIndex.getY(pTileIndex)}&z=${MapTileIndex.getZoom(pTileIndex)}"
    }
}
