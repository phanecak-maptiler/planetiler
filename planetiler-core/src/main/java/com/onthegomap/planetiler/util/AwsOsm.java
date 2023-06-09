package com.onthegomap.planetiler.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import javax.annotation.concurrent.Immutable;

/**
 * A utility to download {@code planet.osm.pbf} files from public S3 sources such as
 * <a href="https://registry.opendata.aws/osm/">AWS Open Data Registry</a> and
 * <a href="https://overturemaps.org">Overture Maps Foundation</a>.
 */
public class AwsOsm {
  public static final AwsOsm OSM_PDS = new AwsOsm("https://osm-pds.s3.amazonaws.com/");
  public static final AwsOsm OVERTURE = new AwsOsm("https://overturemaps-us-west-2.s3.amazonaws.com/");
  private static final ObjectMapper mapper = new XmlMapper().registerModule(new Jdk8Module());

  private final String bucketIndexUrl;
  private volatile IndexXml index = null;

  protected AwsOsm(String bucketIndexUrl) {
    this.bucketIndexUrl = bucketIndexUrl;
  }

  /**
   * Fetches the S3 bucket index and searches for a {@code .osm.pbf} resource to download where snapshot date matches
   * {@code searchQuery}, or the latest snapshot if {@code searchQuery == "latest"}.
   * <p>
   * The index is only fetched once and cached after that.
   *
   * @param searchQuery the snapshot to search for
   * @param config      planetiler config with user-agent and timeout to use when downloading
   * @return the URL of a {@code .osm.pbf} file with name or snapshot ID matching {@code searchQuery}
   * @throws IllegalArgumentException if no matches, or more than one match is found.
   */
  public String getDownloadUrl(String searchQuery, PlanetilerConfig config) {
    IndexXml indexXml = getAndCacheIndex(config);
    return searchIndexForDownloadUrl(searchQuery, indexXml);
  }

  private synchronized IndexXml getAndCacheIndex(PlanetilerConfig config) {
    if (index == null) {
      try (InputStream inputStream = Downloader.openStream(bucketIndexUrl, config)) {
        index = parseIndexXml(inputStream);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return index;
  }

  protected IndexXml parseIndexXml(InputStream indexXmlContent) throws IOException {
    return mapper.readValue(indexXmlContent, IndexXml.class);
  }

  protected String searchIndexForDownloadUrl(String searchQuery, IndexXml index) {
    if ("latest".equalsIgnoreCase(searchQuery)) {
      return index.contents.stream()
        .filter(c -> c.key.endsWith(".osm.pbf"))
        .map(c -> bucketIndexUrl + c.key)
        .max(Comparator.naturalOrder())
        .orElseThrow(() -> new IllegalArgumentException("Unable to find latest AWS osm download URL"));
    } else {
      List<String> results = index.contents.stream()
        .filter(c -> c.key.endsWith("/planet-" + searchQuery + ".osm.pbf"))
        .map(c -> bucketIndexUrl + c.key)
        .toList();
      if (results.isEmpty()) {
        throw new IllegalArgumentException("Unable to find AWS osm download URL for " + searchQuery);
      } else if (results.size() > 1) {
        throw new IllegalArgumentException("Found multiple AWS osm download URLs for " + searchQuery + ": " + results);
      }
      return results.get(0);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @Immutable
  record IndexXml(
    @JacksonXmlProperty(localName = "Contents")
    @JacksonXmlElementWrapper(useWrapping = false) List<ContentXml> contents
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ContentXml(
    @JacksonXmlProperty(localName = "Key") String key
  ) {}
}
