package com.milesight.beaveriot.integrations.edgeaistream;

import com.milesight.beaveriot.base.response.ResponseBody;
import com.milesight.beaveriot.base.response.ResponseBuilder;
import com.milesight.beaveriot.integrations.edgeaistream.model.StreamSource;
import com.milesight.beaveriot.integrations.edgeaistream.model.request.StreamSourceRequest;
import com.milesight.beaveriot.integrations.edgeaistream.model.response.StreamSourceResponse;
import com.milesight.beaveriot.integrations.edgeaistream.service.StreamSourceService;
import com.milesight.beaveriot.integrations.edgeaistream.util.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manages the EdgeAI boxes whose camera pipelines can be relayed.
 * <p>
 * Mounted on a separate path from the relay itself, which is what lets the relay be
 * unauthenticated while these endpoints stay behind normal authentication - only
 * {@code /streams/**} is listed in {@code oauth2.ignore-urls}.
 *
 * @author cytron
 */
@RestController
@RequestMapping("/" + Constants.INTEGRATION_ID + "/sources")
public class EdgeAiStreamSourceController {
    @Autowired
    StreamSourceService streamSourceService;

    @GetMapping
    public ResponseBody<List<StreamSourceResponse>> listSources() {
        List<StreamSourceResponse> sources = streamSourceService.listSources().stream()
                .map(this::toResponse)
                .toList();
        return ResponseBuilder.success(sources);
    }

    @PostMapping
    public ResponseBody<StreamSourceResponse> createSource(@RequestBody StreamSourceRequest request) {
        StreamSource source = streamSourceService.createSource(
                request.getName(), request.getHost(), request.getApiKey());
        return ResponseBuilder.success(toResponse(source));
    }

    @PutMapping("/{id}")
    public ResponseBody<StreamSourceResponse> updateSource(@PathVariable("id") String id,
                                                           @RequestBody StreamSourceRequest request) {
        StreamSource source = streamSourceService.updateSource(
                id, request.getName(), request.getHost(), request.getApiKey());
        return ResponseBuilder.success(toResponse(source));
    }

    @DeleteMapping("/{id}")
    public ResponseBody<Void> deleteSource(@PathVariable("id") String id) {
        streamSourceService.deleteSource(id);
        return ResponseBuilder.success();
    }

    private StreamSourceResponse toResponse(StreamSource source) {
        return StreamSourceResponse.of(source,
                streamSourceService.getApiKey(source.getId()).isPresent(),
                Constants.INTEGRATION_ID);
    }
}
