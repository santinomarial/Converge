package io.converge.connectors;

import java.io.Reader;
import java.util.List;

public interface WarehouseFeedParser {
    List<WarehouseFeedRow> parse(Reader reader);
}

