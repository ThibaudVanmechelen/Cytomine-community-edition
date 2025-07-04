package be.cytomine.controller.ontology;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.io.ParseException;
import org.springframework.cloud.gateway.mvc.ProxyExchange;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.util.UriComponentsBuilder;

import be.cytomine.controller.RestCytomineController;
import be.cytomine.domain.CytomineDomain;
import be.cytomine.domain.image.ImageInstance;
import be.cytomine.domain.ontology.*;
import be.cytomine.domain.security.SecUser;
import be.cytomine.dto.annotation.SimplifiedAnnotation;
import be.cytomine.dto.image.CropParameter;
import be.cytomine.exceptions.CytomineMethodNotYetImplementedException;
import be.cytomine.exceptions.ForbiddenException;
import be.cytomine.exceptions.ObjectNotFoundException;
import be.cytomine.exceptions.WrongArgumentException;
import be.cytomine.repository.*;
import be.cytomine.service.AnnotationListingService;
import be.cytomine.service.image.ImageInstanceService;
import be.cytomine.service.middleware.ImageServerService;
import be.cytomine.service.ontology.AlgoAnnotationService;
import be.cytomine.service.ontology.GenericAnnotationService;
import be.cytomine.service.ontology.ReviewedAnnotationService;
import be.cytomine.service.ontology.UserAnnotationService;
import be.cytomine.service.security.SecUserService;
import be.cytomine.service.utils.ParamsService;
import be.cytomine.service.utils.SimplifyGeometryService;
import be.cytomine.utils.AnnotationListingBuilder;
import be.cytomine.utils.GeometryUtils;
import be.cytomine.utils.JsonObject;
import be.cytomine.config.properties.ApplicationProperties;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api")
@RestController
public class RestAnnotationDomainController extends RestCytomineController {

    private final ApplicationProperties applicationProperties;

    private final RestTemplate restTemplate;

    private final AnnotationListingService annotationListingService;

    private final GenericAnnotationService genericAnnotationService;

    private final SecUserService secUserService;

    private final EntityManager entityManager;
    
    private final ParamsService paramsService;

    private final RestUserAnnotationController restUserAnnotationController;

    private final RestAlgoAnnotationController restAlgoAnnotationController;

    private final RestReviewedAnnotationController restReviewedAnnotationController;

    private final ImageServerService imageServerService;

    private final ImageInstanceService imageInstanceService;

    private final ReviewedAnnotationService reviewedAnnotationService;

    private final UserAnnotationService userAnnotationService;

    private final AlgoAnnotationService algoAnnotationService;

    private final SimplifyGeometryService simplifyGeometryService;

    private final AnnotationListingBuilder annotationListingBuilder;

    @RequestMapping(value = { "/annotation/search.json"}, method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> searchSpecified() throws IOException {
        return search();
    }

    @RequestMapping(value = {"/annotation.json"}, method = {RequestMethod.GET})
    public ResponseEntity<String> search() throws IOException {
        JsonObject params = mergeQueryParamsAndBodyParams();
        AnnotationListing annotationListing = annotationListingBuilder.buildAnnotationListing(params);
        List annotations = annotationListingService.listGeneric(annotationListing);
        if (annotationListing instanceof AlgoAnnotationListing) {
            //if algo, we look for user_annotation JOIN algo_annotation_term  too
            params.put("suggestedTerm", params.get("term"));
            params.remove("term");
            params.remove("usersForTermAlgo");
            annotationListing = annotationListingBuilder.buildAnnotationListing(new UserAnnotationListing(entityManager), params);
            annotations.addAll(annotationListingService.listGeneric(annotationListing));
        }

        return responseSuccess(annotations, params.getJSONAttrLong("offset", 0L),params.getJSONAttrLong("max", 0L));
    }

    @RequestMapping(value = {"/project/{project}/annotation/download"}, method = {RequestMethod.GET})
    public void download(
            @PathVariable Long project,
            @RequestParam String format,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String reviewUsers,
            @RequestParam(defaultValue = "false") Boolean reviewed,
            @RequestParam(required = false) String terms,
            @RequestParam(required = false) String images,
            @RequestParam(required = false) Long beforeThan,
            @RequestParam(required = false) Long afterThan
    ) throws IOException {
        if(reviewed) {
            restReviewedAnnotationController.downloadDocumentByProject(project, format, terms, reviewUsers, images, beforeThan, afterThan);
        }
        else {
            if ((users != null && !users.isEmpty()) && false) { // SecUser.read(users.first()).algo()
                restAlgoAnnotationController.downloadDocumentByProject(project, format, terms, users, images, beforeThan, afterThan);
            } else {
                restUserAnnotationController.downloadDocumentByProject(project, format, terms, users, images, beforeThan, afterThan);
            }
        }
    }

    @RequestMapping(value = "/annotation/{id}/crop.{format}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> crop(
            @PathVariable Long id,
            @PathVariable String format,
            @RequestParam(required = false) Integer maxSize,
            @RequestParam(required = false) String geometry,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String boundaries,
            @RequestParam(defaultValue = "false") Boolean complete,
            @RequestParam(required = false) Integer zoom,
            @RequestParam(required = false) Double increaseArea,
            @RequestParam(required = false) Boolean safe,
            @RequestParam(required = false) Boolean square,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean draw,
            @RequestParam(required = false) Boolean mask,
            @RequestParam(required = false) Boolean alphaMask,
            @RequestParam(required = false) Boolean drawScaleBar,
            @RequestParam(required = false) Double resolution,
            @RequestParam(required = false) Double magnification,
            @RequestParam(required = false) String colormap,
            @RequestParam(required = false) Boolean inverse,
            @RequestParam(required = false) Double contrast,
            @RequestParam(required = false) Double gamma,
            @RequestParam(required = false) String bits,
            @RequestParam(required = false) Integer alpha,
            @RequestParam(required = false) Integer thickness,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) Integer jpegQuality,

            ProxyExchange<byte[]> proxy
    ) throws IOException, ParseException {
        log.debug("REST request to get crop for annotation domain");
        AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);

        CropParameter cropParameter = new CropParameter();
        cropParameter.setGeometry(geometry);
        cropParameter.setLocation(location);
        cropParameter.setComplete(complete);
        cropParameter.setZoom(zoom);
        cropParameter.setIncreaseArea(increaseArea);
        cropParameter.setSafe(safe);
        cropParameter.setSquare(square);
        cropParameter.setType(type);
        cropParameter.setDraw(draw);
        cropParameter.setMask(mask);
        cropParameter.setAlphaMask(alphaMask);
        cropParameter.setDrawScaleBar(drawScaleBar);
        cropParameter.setResolution(resolution);
        cropParameter.setMagnification(magnification);
        cropParameter.setColormap(colormap);
        cropParameter.setInverse(inverse);
        cropParameter.setGamma(gamma);
        cropParameter.setMaxSize(maxSize);
        cropParameter.setAlpha(alpha);
        cropParameter.setContrast(contrast);
        cropParameter.setThickness(thickness);
        cropParameter.setColor(color);
        cropParameter.setJpegQuality(jpegQuality);
        cropParameter.setMaxBits(bits!=null && bits.equals("max"));
        cropParameter.setBits(bits!=null && !bits.equals("max") ? Integer.parseInt(bits): null);
        cropParameter.setFormat(format);
        String etag = getRequestETag();
        return imageServerService.crop(annotation, cropParameter, etag, proxy);
    }

    @GetMapping("/imageinstance/{image}/annotation/included.json")
    public ResponseEntity<String> listIncludedAnnotation(
            @PathVariable(name="image") Long imageId
    ) throws IOException {
        JsonObject jsonObject = mergeQueryParamsAndBodyParams();
        jsonObject.put("image", imageId);
        return responseSuccess(getIncludedAnnotation(
                jsonObject,
                paramsService.getPropertyGroupToShow(jsonObject)
        ));
    }

    private List getIncludedAnnotation(JsonObject params, List<String> propertiesToShow){

        ImageInstance image = imageInstanceService.find(params.getJSONAttrLong("image"))
                .orElseThrow(() -> new ObjectNotFoundException("ImageInstance", params.getJSONAttrStr("image")));

        //get area
        String geometry = params.getJSONAttrStr("geometry");
        AnnotationDomain annotation = null;
        if(geometry==null) {
            annotation = AnnotationDomain.getAnnotationDomain(entityManager, params.getJSONAttrLong("annotation"));
            geometry = annotation.getLocation().toText();
        }

        //get user
        Long idUser = params.getJSONAttrLong("user");
        SecUser user = null;
        if (idUser!=0) {
            user = secUserService.find(params.getJSONAttrLong("user")).orElse(null);
        }

        //get term
        List<Long> terms = paramsService.getParamsTermList(params.getJSONAttrStr("terms"),image.getProject());

        List response;
        if(user==null) {
            //goto reviewed
            response = reviewedAnnotationService.listIncluded(image,geometry,terms,annotation,propertiesToShow);
        } else if (user.isAlgo()) {
            //goto algo
            response = algoAnnotationService.listIncluded(image,geometry,user,terms,annotation,propertiesToShow);
        }  else {
            //goto user annotation
            response = userAnnotationService.listIncluded(image,geometry,user,terms,annotation,propertiesToShow);
        }
        return response;
    }

    /**
     * Read a specific annotation
     * It's better to avoid the user of this method if we know the correct type of an annotation id
     * Annotation x => annotation/x.json is slower than userannotation/x.json or algoannotation/x.json
     */
    @RequestMapping(value = "/annotation/{id}.json", method = {RequestMethod.GET})
    public ResponseEntity<String> show(@PathVariable Long id) throws IOException {
        AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);
        if (annotation.isUserAnnotation()) {
            return restUserAnnotationController.show(id);
        } else if (annotation.isAlgoAnnotation()) {
            return restAlgoAnnotationController.show(id);
        } else if (annotation.isReviewedAnnotation()) {
            return restReviewedAnnotationController.show(id);
        } else {
            throw new CytomineMethodNotYetImplementedException("ROI annotation not yet implemented");
        }
    }

    /**
     * Add an annotation
     * Redirect to the controller depending on the user type
     */
    @RequestMapping(value = "/annotation.json", method = {RequestMethod.POST})
    public ResponseEntity<String> add(@RequestBody String json,
                                      @RequestParam(required = false, defaultValue = "false") Boolean roi,
                                      @RequestParam(required = false) Long minPoint,
                                      @RequestParam(required = false) Long maxPoint
    ) throws IOException {
        log.debug("REST request to create new annotation(s)");
        SecUser secUser = secUserService.getCurrentUser();
        if(roi) {
            throw new CytomineMethodNotYetImplementedException("ROI annotation not yet implemented");
        } else if (secUser.isAlgo()) {
            return restAlgoAnnotationController.add(json, minPoint, maxPoint);
        } else {
            ResponseEntity<String> response = restUserAnnotationController.add(json, minPoint, maxPoint);
            log.debug("REST request to create new annotation(s) finished");
            return response;
        }
    }

    /**
     * Update an annotation
     * Redirect to the good controller with the annotation type
     */
    @RequestMapping(value = "/annotation/{id}.json", method = {RequestMethod.PUT})
    public ResponseEntity<String> update(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") Boolean fill,
            @RequestBody JsonObject jsonObject

    ) throws IOException {
        if (fill) {
            return fillAnnotation(id);
        } else {
            AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);
            if (annotation.isUserAnnotation()) {
                return restUserAnnotationController.edit(id.toString(), jsonObject);
            } else if (annotation.isAlgoAnnotation()) {
                return restAlgoAnnotationController.edit(id.toString(), jsonObject);
            } else if (annotation.isReviewedAnnotation()) {
                return restReviewedAnnotationController.edit(id.toString(), jsonObject);
            } else {
                throw new CytomineMethodNotYetImplementedException("ROI annotation not yet implemented");
            }
        }
    }

    /**
     * Delete an annotation
     * Redirect to the good controller with the current user type
     */
    @RequestMapping(value = "/annotation/{id}.json", method = {RequestMethod.DELETE})
    public ResponseEntity<String> delete(
            @PathVariable Long id
    ) throws IOException {
        AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);
        if (annotation.isUserAnnotation()) {
            return restUserAnnotationController.delete(id.toString());
        } else if (annotation.isAlgoAnnotation()) {
            return restAlgoAnnotationController.delete(id.toString());
        } else if (annotation.isReviewedAnnotation()) {
            return restReviewedAnnotationController.delete(id.toString());
        } else {
            throw new CytomineMethodNotYetImplementedException("ROI annotation not yet implemented");
        }
    }

    @RequestMapping(value = "/annotation/{id}/simplify.json", method = {RequestMethod.PUT})
    public ResponseEntity<String> simplify(
            @PathVariable Long id,
            @RequestParam(required = false) Long minPoint,
            @RequestParam(required = false) Long maxPoint

    )  {
        AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);
        SimplifiedAnnotation simplifiedAnnotation = simplifyGeometryService.simplifyPolygon(annotation.getLocation(), minPoint, maxPoint);
        annotation.setLocation(simplifiedAnnotation.getNewAnnotation());
        annotation.setGeometryCompression(simplifiedAnnotation.getRate());
        userAnnotationService.saveDomain(annotation);
        return responseSuccess(annotation);
    }

    @RequestMapping(value = "/simplify.json", method = {RequestMethod.PUT})
    public ResponseEntity<String> retrieveSimplify(
            @RequestBody JsonObject jsonObject,
            @RequestParam(required = false) Long minPoint,
            @RequestParam(required = false) Long maxPoint

    )  {
        SimplifiedAnnotation simplifiedAnnotation = simplifyGeometryService.simplifyPolygon(jsonObject.getJSONAttrStr("wkt"), minPoint, maxPoint);
        return responseSuccess(JsonObject.of("wkt", simplifiedAnnotation.getNewAnnotation().toText()));
    }

    /**
     * Fill an annotation.
     * Remove empty space in the polygon
     */
    @RequestMapping(value = "/annotation/{id}/fill.json", method = {RequestMethod.POST}) // TODO: should be PUT
    public ResponseEntity<String> fillAnnotation(
            @PathVariable Long id
    ) throws IOException {
        AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);

        //Is the first polygon always the big 'boundary' polygon?
        String newGeom = GeometryUtils.fillPolygon(annotation.getLocation().toText());
        JsonObject jsonObject = annotation.toJsonObject()
                .withChange("location", newGeom);

        if (annotation.isUserAnnotation()) {
            return responseSuccess(userAnnotationService.update(annotation, jsonObject));
        } else if (annotation.isAlgoAnnotation()) {
            return responseSuccess(algoAnnotationService.update(annotation, jsonObject));
        } else  {
            return responseSuccess(reviewedAnnotationService.update(annotation, jsonObject));
        }
    }

    /**
     * Add/Remove a geometry Y to/from the annotation geometry X.
     * Y must have intersection with X
     */
    @PostMapping("/annotationcorrection.json")
    public ResponseEntity<String> addCorrection(
            @RequestBody JsonObject jsonObject
    ) throws ParseException {
        String location = jsonObject.getJSONAttrStr("location");
        List<Long> layers = jsonObject.getJSONAttrListLong("layers");
        Long image = jsonObject.getJSONAttrLong("image");
        Boolean remove = jsonObject.getJSONAttrBoolean("remove", false);

        List<Long> idsReviewedAnnotation = new ArrayList<>();
        List<Long> idsUserAnnotation = new ArrayList<>();
        if (jsonObject.containsKey("annotation")) {
            if (jsonObject.getJSONAttrBoolean("review", false)) {
                idsReviewedAnnotation.add(jsonObject.getJSONAttrLong("annotation"));
            } else {
                idsUserAnnotation.add(jsonObject.getJSONAttrLong("annotation"));
            }
        } else {

            //if review mode, priority is done to reviewed annotation correction
            if (jsonObject.getJSONAttrBoolean("review", false)) {
                idsReviewedAnnotation = genericAnnotationService.findAnnotationThatTouch(location, layers, image, "reviewed_annotation")
                        .stream().map(CytomineDomain::getId).collect(Collectors.toList());
            }

            //there is no reviewed intersect annotation or user is not in review mode
            if (idsReviewedAnnotation.isEmpty()) {
                idsUserAnnotation = genericAnnotationService.findAnnotationThatTouch(location, layers, image, "user_annotation")
                        .stream().map(CytomineDomain::getId).collect(Collectors.toList());
            }
        }
        log.info("idsReviewedAnnotation="+idsReviewedAnnotation);
        log.info("idsUserAnnotation="+idsUserAnnotation);

        //there is no user/reviewed intersect
        if (idsUserAnnotation.isEmpty() && idsReviewedAnnotation.isEmpty()) {
            throw new WrongArgumentException("There is no intersect annotation!");
        }

        if (idsUserAnnotation.isEmpty()) {
            return responseSuccess(reviewedAnnotationService.doCorrectReviewedAnnotation(idsReviewedAnnotation, location, remove));
        } else {
            return responseSuccess(userAnnotationService.doCorrectUserAnnotation(idsUserAnnotation, location, remove));
        }
    }


    @PostMapping("/annotation/{id}/sam")
    public ResponseEntity<JsonObject> processAnnotationWithSam(@PathVariable Long id) {
        long startTotal = System.nanoTime(); // Start total execution time
    
        long start = System.nanoTime();
        AnnotationDomain annotation = AnnotationDomain.getAnnotationDomain(entityManager, id);
        long end = System.nanoTime();
        System.out.println("Time to fetch annotation: " + (end - start) / 1_000_000.0 + " ms");
    
        start = System.nanoTime();
        if (!annotation.isUserAnnotation()) {
            System.out.println("Annotation is not a user annotation (early exit).");
            throw new WrongArgumentException("Only user annotations can be processed with SAM.");
        }
        end = System.nanoTime();
        System.out.println("Time to check user annotation: " + (end - start) / 1_000_000.0 + " ms");
    
        start = System.nanoTime();
        Long userId = AnnotationDomain.getDataFromDomain(annotation).getJSONAttrLong("user");
        Long secUserId = secUserService.getCurrentUser().getId();
    
        if (!userId.equals(secUserId)) {
            System.out.println("User is not authorized (early exit).");
            throw new ForbiddenException("You are not allowed to process this annotation with SAM");
        }
        end = System.nanoTime();
        System.out.println("Time to verify user permissions: " + (end - start) / 1_000_000.0 + " ms");
    
        start = System.nanoTime();
        URI url = UriComponentsBuilder
            .fromHttpUrl(applicationProperties.getInternalProxyURL())
            .path("/sam/autonomous_prediction")
            .queryParam("annotation_id", id)
            .build()
            .toUri();
        end = System.nanoTime();
        System.out.println("Time to build SAM URL: " + (end - start) / 1_000_000.0 + " ms");
    
        try {
            start = System.nanoTime();
            ResponseEntity<String> samResponse = restTemplate.postForEntity(url, null, String.class);
            end = System.nanoTime();
            System.out.println("Time to call SAM server: " + (end - start) / 1_000_000.0 + " ms");
    
            JsonObject json = new JsonObject();
            json.put("message", samResponse.getBody());
    
            long endTotal = System.nanoTime();
            System.out.println("Total execution time: " + (endTotal - startTotal) / 1_000_000.0 + " ms");
    
            return ResponseEntity.status(samResponse.getStatusCode()).body(json);
    
        } catch (HttpStatusCodeException e) {
            System.out.println("HttpStatusCodeException occurred after " + (System.nanoTime() - startTotal) / 1_000_000.0 + " ms");
            JsonObject json = new JsonObject();
            json.put("message", e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(json);
    
        } catch (Exception e) {
            System.out.println("Exception occurred after " + (System.nanoTime() - startTotal) / 1_000_000.0 + " ms");
            JsonObject json = new JsonObject();
            json.put("message", "Failed to call SAM server: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(json);
        }
    }
}
