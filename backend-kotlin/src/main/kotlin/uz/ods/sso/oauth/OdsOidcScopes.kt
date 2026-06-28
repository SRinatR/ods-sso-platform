package uz.ods.sso.oauth

import org.springframework.security.oauth2.core.oidc.OidcScopes

object OdsOidcScopes {
    const val FULL_NAME_CYRILLIC = "full_name_cyrillic"
    const val FULL_NAME_LATIN = "full_name_latin"
    const val CONSENT_UI_POLICY = "consent_ui"
    const val CONSENT_LAYOUT_CLASSIC = "classic"
    const val CONSENT_LAYOUT_GRANULAR = "granular"

    val consentLayouts = setOf(CONSENT_LAYOUT_CLASSIC, CONSENT_LAYOUT_GRANULAR)
    val requiredConsentScopes = setOf(OidcScopes.OPENID, OidcScopes.EMAIL)
}
