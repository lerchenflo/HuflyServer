package com.lerchenflo.hufly.server.website

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.ResourceTransformer
import org.springframework.web.servlet.resource.ResourceTransformerChain
import org.springframework.web.servlet.resource.TransformedResource
import org.springframework.web.util.HtmlUtils


/** Strict on purpose: the address is pasted into HTML, mailto links and JavaScript strings unescaped. */
private val SAFE_EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

fun requireContactEmail(raw: String): String {
    val email = raw.trim()
    check(SAFE_EMAIL.matches(email)) { "website.contact-email (CONTACT_EMAIL) must be a plain email address, got '$raw'" }
    return email
}

/** Operator details for the Impressum; required so no placeholder goes live. Escaped when pasted into the pages. */
fun requireImprintValue(property: String, raw: String): String {
    val value = raw.trim()
    check(value.isNotEmpty() && value.length <= 200 && value.none { it == '\n' || it == '\r' }) {
        "$property must be set to a single line of at most 200 characters"
    }
    return value
}

/** Only the markup characters; umlauts stay readable since the pages are UTF-8. */
private fun htmlEscape(value: String): String = HtmlUtils.htmlEscape(value, "UTF-8")

/** Serves the static website like Spring Boot does, with `{{CONTACT_EMAIL}}` and the `{{IMPRINT_*}}` values filled in on HTML pages. */
@Configuration
class WebsiteConfig(
    @Value("\${website.contact-email}") contactEmail: String,
    @Value("\${website.imprint-name}") imprintName: String,
    @Value("\${website.imprint-street}") imprintStreet: String,
    @Value("\${website.imprint-city}") imprintCity: String,
) : WebMvcConfigurer {

    private val values = mapOf(
        "{{CONTACT_EMAIL}}" to requireContactEmail(contactEmail),
        "{{IMPRINT_NAME}}" to htmlEscape(requireImprintValue("website.imprint-name", imprintName)),
        "{{IMPRINT_STREET}}" to htmlEscape(requireImprintValue("website.imprint-street", imprintStreet)),
        "{{IMPRINT_CITY}}" to htmlEscape(requireImprintValue("website.imprint-city", imprintCity)),
    )

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addTransformer(PlaceholderTransformer(values))
    }
}

private class PlaceholderTransformer(private val values: Map<String, String>) : ResourceTransformer {
    override fun transform(request: HttpServletRequest, resource: Resource, chain: ResourceTransformerChain): Resource {
        val transformed = chain.transform(request, resource)
        if (transformed.filename?.endsWith(".html") != true) return transformed
        val html = transformed.contentAsByteArray.toString(Charsets.UTF_8)
        val filled = values.entries.fold(html) { page, (placeholder, value) -> page.replace(placeholder, value) }
        return TransformedResource(transformed, filled.toByteArray(Charsets.UTF_8))
    }
}
