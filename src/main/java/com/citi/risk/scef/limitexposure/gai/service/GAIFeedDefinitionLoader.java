package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import javax.inject.Inject;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and validates feed definition YAML files from:
 *   classpath:gai/feed-definitions/{feedName}.yml
 *
 * SnakeYAML 2.x API:
 *   Constructor(Class, LoaderOptions) — replaces Constructor(Class) from 1.x
 *   yaml.load(InputStream)           — replaces loadAs(InputStream, Class)
 */
public class GAIFeedDefinitionLoader {

    private static final Logger logger = LoggerFactory.getLogger(GAIFeedDefinitionLoader.class);

    private final Map<String, FeedDefinition> cache = new ConcurrentHashMap<String, FeedDefinition>();

    @Inject
    public GAIFeedDefinitionLoader() { }

    public FeedDefinition load(String feedName) {
        FeedDefinition cached = cache.get(feedName);
        if (cached != null) return cached;
        FeedDefinition loaded = loadFromClasspath(feedName);
        cache.put(feedName, loaded);
        return loaded;
    }

    private FeedDefinition loadFromClasspath(String feedName) {
        String path = "gai/feed-definitions/" + feedName + ".yml";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Feed definition not found on classpath: " + path);
            }
            // SnakeYAML 2.x: Constructor(Class, LoaderOptions)
            // SnakeYAML 1.x used Constructor(Class) — removed in 2.x
            // LoaderOptions uses defaults (max aliases=50, code points=3MB)
            Yaml yaml = new Yaml(new Constructor(FeedDefinition.class, new LoaderOptions()));
            FeedDefinition def = yaml.load(is);
            if (def == null) {
                throw new IllegalStateException("Feed definition is empty: " + path);
            }
            validate(def, feedName);
            logger.info("[GAI] Loaded feed definition feedName={} feedId={} frequency={}",
                        def.getFeedName(), def.getGaiFeedId(), def.getFrequency());
            return def;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load feed definition: " + path, e);
        }
    }

    private void validate(FeedDefinition def, String requestedFeedName) {
        if (def.getFeedName() == null || def.getFeedName().trim().length() == 0) {
            throw new IllegalStateException("feedName is mandatory in YAML: " + requestedFeedName);
        }
        if (!requestedFeedName.equals(def.getFeedName())) {
            throw new IllegalStateException("YAML feedName mismatch. requested=" +
                    requestedFeedName + " yaml=" + def.getFeedName());
        }
        if (def.getColumnsForType("EVENT").isEmpty()) {
            throw new IllegalStateException("eventFields missing in YAML: " + requestedFeedName);
        }
        if (def.getColumnsForType("RECORD").isEmpty()) {
            throw new IllegalStateException("recordFields missing in YAML: " + requestedFeedName);
        }
        if (def.getColumnsForType("ATTRIBUTE").isEmpty()) {
            throw new IllegalStateException("attributeFields missing in YAML: " + requestedFeedName);
        }
    }
}
