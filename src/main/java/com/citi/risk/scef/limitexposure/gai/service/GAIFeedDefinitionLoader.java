package com.citi.risk.scef.limitexposure.gai.service;

import com.citi.risk.scef.limitexposure.gai.domain.FeedDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Place YAML files in:
 *   scef-war/src/main/resources/gai/feed-definitions/
 *
 * Results are cached after first load.
 * SnakeYAML is a transitive Spring dependency — already available in scef-war.
 */
public class GAIFeedDefinitionLoader {

    private static final Logger logger = LoggerFactory.getLogger(GAIFeedDefinitionLoader.class);

    // Java 8 compatible explicit type parameter
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
            Yaml yaml = new Yaml(new Constructor(FeedDefinition.class));
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
