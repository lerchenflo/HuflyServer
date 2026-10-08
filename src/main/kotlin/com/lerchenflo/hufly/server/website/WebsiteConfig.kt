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

const val CONTACT_EMAIL_PLACEHOLDER = "{{CONTACT_EMAIL}}"

/** Strict on purpose: the address is pasted into HTML, mailto links and JavaScript strings unescaped. */
private val SAFE_EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

fun requireContactEmail(raw: String): String {
    val email = raw.trim()
    check(SAFE_EMAIL.matches(email)) { "website.contact-email (CONTACT_EMAIL) must be a plain email address, got '$raw'" }
    return email
}

/** Serves the static website like Spring Boot does, with [CONTACT_EMAIL_PLACEHOLDER] filled in on HTML pages. */
@Configuration
class WebsiteConfig(@Value("\${website.contact-email}") contactEmail: String) : WebMvcConfigurer {

    private val contactEmail = requireContactEmail(contactEmail)

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addTransformer(ContactEmailTransformer(this.contactEmail))
    }
}

private class ContactEmailTransformer(private val contactEmail: String) : ResourceTransformer {
    override fun transform(request: HttpServletRequest, resource: Resource, chain: ResourceTransformerChain): Resource {
        val transformed = chain.transform(request, resource)
        if (transformed.filename?.endsWith(".html") != true) return transformed
        val html = transformed.contentAsByteArray.toString(Charsets.UTF_8)
        return TransformedResource(transformed, html.replace(CONTACT_EMAIL_PLACEHOLDER, contactEmail).toByteArray(Charsets.UTF_8))
    }
}
