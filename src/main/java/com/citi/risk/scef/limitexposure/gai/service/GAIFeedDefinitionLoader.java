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
 * Loads feed definition YAML files from:
 *   classpath:gai/feed-definitions/{feedName}.yml
 *
 * Files live in:
 *   scef-war/src/main/resources/gai/feed-definitions/
 *
 * Results are cached in memory after first load.
 * SnakeYAML is already a transitive dependency via Spring in scef-war.
 */
public class GAIFeedDefinitionLoader {

    private static final Logger logger =
            LoggerFactory.getLogger(GAIFeedDefinitionLoader.class);

    private final Map<String, FeedDefinition> cache = new ConcurrentHashMap<>();

    @Inject
    public GAIFeedDefinitionLoader() {}

    public FeedDefinition load(String feedName) {
        return cache.computeIfAbsent(feedName, this::loadFromClasspath);
    }

    private FeedDefinition loadFromClasspath(String feedName) {
        String path = "gai/feed-definitions/" + feedName + ".yml";
        logger.debug("[GAI] Loading feed definition: {}", path);

        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Feed definition not found: " + path);
            }
            Yaml yaml = new Yaml(new Constructor(FeedDefinition.class));
            FeedDefinition def = yaml.load(is);
            logger.info("[GAI] Loaded definition for [{}] — perspective={}",
                        feedName, def.getPerspectiveName());
            return def;
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to load feed definition: " + path, e);
        }
    }
}
