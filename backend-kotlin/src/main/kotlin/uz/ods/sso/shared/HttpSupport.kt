package uz.ods.sso.shared

import jakarta.servlet.http.HttpServletRequest
import uz.ods.sso.config.OdsProperties

fun clientIp(request: HttpServletRequest, properties: OdsProperties): String {
    val forwarded = request.getHeader("X-Forwarded-For")
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    return if (properties.trustedProxyCount > 0 && forwarded.size >= properties.trustedProxyCount) {
        forwarded[forwarded.size - properties.trustedProxyCount]
    } else {
        request.remoteAddr ?: "unknown"
    }
}
