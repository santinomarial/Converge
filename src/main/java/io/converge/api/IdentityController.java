package io.converge.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.converge.identity.IdentityService;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    private final IdentityService identity;

    public IdentityController(IdentityService identity) {
        this.identity = identity;
    }

    @PostMapping("/skus")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createSku(@RequestBody CreateSku request) {
        return new IdResponse(identity.createSku(
                request.sku(), request.style(), request.color(), request.size(), request.skuClass()));
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createLocation(@RequestBody CreateLocation request) {
        return new IdResponse(identity.createLocation(request.code(), request.name(), request.type()));
    }

    @PostMapping("/sku-mappings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mapSku(@RequestBody MapSku request) {
        identity.mapSku(request.canonicalSkuId(), request.system(), request.externalId());
    }

    @PostMapping("/location-mappings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mapLocation(@RequestBody MapLocation request) {
        identity.mapLocation(request.locationId(), request.system(), request.externalId());
    }

    public record CreateSku(String sku, String style, String color, String size, String skuClass) {
    }

    public record CreateLocation(String code, String name, String type) {
    }

    public record MapSku(long canonicalSkuId, String system, String externalId) {
    }

    public record MapLocation(long locationId, String system, String externalId) {
    }

    public record IdResponse(long id) {
    }
}

