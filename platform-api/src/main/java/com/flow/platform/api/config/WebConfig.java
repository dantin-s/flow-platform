/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.api.config;

import com.flow.platform.api.multipart.FlowMultipartMatcher;
import com.flow.platform.api.multipart.FlowMultipartResolver;
import com.flow.platform.api.resource.PropertyResourceLoader;
import com.flow.platform.api.security.AuthenticationInterceptor;
import com.flow.platform.api.security.OptionsInterceptor;
import com.flow.platform.api.security.token.JwtTokenGenerator;
import com.flow.platform.api.security.token.TokenGenerator;
import com.flow.platform.core.http.converter.RawGsonMessageConverter;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.util.resource.AppResourceLoader;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.apache.commons.io.Charsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

@Configuration
@EnableWebMvc
@ComponentScan({
    "com.flow.platform.core.controller",
    "com.flow.platform.api.controller",
    "com.flow.platform.api.service",
    "com.flow.platform.api.envs.handler",
    "com.flow.platform.api.security",
    "com.flow.platform.api.dao",
    "com.flow.platform.api.context",
    "com.flow.platform.api.util",
    "com.flow.platform.api.consumer",
    "com.flow.platform.api.initializers"})
@Import({AppConfig.class})
public class WebConfig extends WebMvcConfigurerAdapter {

    private final static Gson GSON_CONFIG_FOR_RESPONSE = new GsonBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .registerTypeAdapter(ZonedDateTime.class, new ZonedDateTimeAdaptor())
        .create();

    private final static int MAX_UPLOAD_SIZE = 2 * 1024 * 1024;

    private final static int LOCAL_FILE_RESOURCE_MAX_UPLOAD_SIZE = 500 * 1024 * 1024;

    private final RawGsonMessageConverter jsonConverter =
        new RawGsonMessageConverter(true, GSON_CONFIG_FOR_RESPONSE, Jsonable.GSON_CONFIG);

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins("*")
            .allowedMethods("*")
            .allowCredentials(true)
            .allowedHeaders("origin", "content-type", "accept", "x-requested-with", "X-Authorization", "authenticate",
                "library");
    }

    @Bean
    public PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() throws IOException {
        AppResourceLoader propertyLoader = new PropertyResourceLoader();
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setIgnoreResourceNotFound(Boolean.FALSE);
        configurer.setLocation(propertyLoader.find());
        return configurer;
    }

    @Bean(name = "multipartResolver")
    public MultipartResolver multipartResolver() throws IOException {
        List<FlowMultipartMatcher> matchers = ImmutableList.of(
            new FlowMultipartMatcher("/credentials", MAX_UPLOAD_SIZE),
            new FlowMultipartMatcher("/local_file_resources", LOCAL_FILE_RESOURCE_MAX_UPLOAD_SIZE)
        );

        FlowMultipartResolver resolver = new FlowMultipartResolver(matchers);
        return resolver;
    }

    @Bean
    public TokenGenerator tokenGenerator() {
        return new JwtTokenGenerator("MY_SECRET_KEY");
    }

    @Bean
    public AuthenticationInterceptor authInterceptor() {
        List<RequestMatcher> matchers = ImmutableList.of(
            new AntPathRequestMatcher("/flows/**"),
            new AntPathRequestMatcher("/user/register"),
            new AntPathRequestMatcher("/user/delete"),
            new AntPathRequestMatcher("/user"),
            new AntPathRequestMatcher("/user/role/update"),
            new AntPathRequestMatcher("/jobs/**"),
            new AntPathRequestMatcher("/credentials/*"),
            new AntPathRequestMatcher("/actions/**"),
            new AntPathRequestMatcher("/message/**"),
            new AntPathRequestMatcher("/agents/create"),
            new AntPathRequestMatcher("/agents"),
            new AntPathRequestMatcher("/roles/**"),
            new AntPathRequestMatcher("/thread/config")
        );
        return new AuthenticationInterceptor(matchers);
    }


    @Bean
    public RawGsonMessageConverter jsonConverter() {
        return this.jsonConverter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OptionsInterceptor());
        registry.addInterceptor(authInterceptor());
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(converter -> converter.getSupportedMediaTypes().contains(MediaType.APPLICATION_JSON));

        // set response default utf8
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof StringHttpMessageConverter) {
                ((StringHttpMessageConverter) converter).setDefaultCharset(Charsets.UTF_8);
            }
        }

        converters.add(jsonConverter);
    }

    /**
     * Used for convert zoned date time to timestamp
     */
    private static class ZonedDateTimeAdaptor extends TypeAdapter<ZonedDateTime> {

        @Override
        public void write(JsonWriter out, ZonedDateTime value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.value(value.toEpochSecond());
        }

        @Override
        public ZonedDateTime read(JsonReader in) throws IOException {
            Long ts = in.nextLong();
            Instant i = Instant.ofEpochSecond(ts);
            ZonedDateTime z;
            z = ZonedDateTime.ofInstant(i, ZoneId.systemDefault());
            return z;
        }
    }
}
