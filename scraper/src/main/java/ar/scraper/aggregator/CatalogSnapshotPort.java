package ar.scraper.aggregator;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;

public interface CatalogSnapshotPort {

    AggregatedResult getLastResult();
}
