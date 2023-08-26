package com.onthegomap.planetiler.custommap.configschema;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collection;

public record FeatureLayer(
  String id,
  Collection<FeatureItem> features,
  @JsonProperty("tile_post_process") PostProcess postProcess
) {}
