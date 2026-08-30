package io.converge.connectors.warehouse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import io.converge.connectors.WarehouseFeedParser;
import io.converge.connectors.WarehouseFeedRow;

@Component
public class WarehouseCsvAdapter implements WarehouseFeedParser {

    @Override
    public List<WarehouseFeedRow> parse(Reader reader) {
        try (BufferedReader lines = new BufferedReader(reader)) {
            String header = lines.readLine();
            if (!"sku_id,location_id,qty,occurred_at".equals(header)) {
                throw new IllegalArgumentException("Warehouse CSV header is invalid");
            }
            return lines.lines().filter(line -> !line.isBlank()).map(this::parseRow).toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Warehouse CSV could not be read", exception);
        }
    }

    private WarehouseFeedRow parseRow(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Warehouse CSV row must have four columns");
        return new WarehouseFeedRow(fields[0], fields[1], Integer.parseInt(fields[2]), Instant.parse(fields[3]));
    }
}

