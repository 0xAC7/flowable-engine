/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.cmmn.converter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.flowable.cmmn.model.BaseElement;
import org.flowable.cmmn.model.Case;
import org.flowable.cmmn.model.CaseElement;
import org.flowable.cmmn.model.CmmnModel;
import org.flowable.cmmn.model.Criterion;
import org.flowable.cmmn.model.HasEntryCriteria;
import org.flowable.cmmn.model.HasExitCriteria;
import org.flowable.cmmn.model.PlanFragment;
import org.flowable.cmmn.model.PlanItem;
import org.flowable.cmmn.model.PlanItemDefinition;
import org.flowable.cmmn.model.ProcessTask;
import org.flowable.cmmn.model.Sentry;
import org.flowable.cmmn.model.SentryOnPart;
import org.flowable.cmmn.model.Stage;
import org.flowable.cmmn.model.Task;
import org.flowable.engine.common.api.FlowableException;
import org.flowable.engine.common.api.io.InputStreamProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * @author Joram Barrez
 */
public class CmmnXmlConverter implements CmmnXmlConstants {

    protected static final Logger LOGGER = LoggerFactory.getLogger(CmmnXmlConverter.class);
    protected static final String XSD_LOCATION = "org/flowable/impl/cmmn/parser/CMMN11.xsd";
    protected static final String DEFAULT_ENCODING = "UTF-8";

    protected static Map<String, BaseCmmnXmlConverter> elementConverters = new HashMap<>();
    protected static Map<String, BaseCmmnXmlConverter> textConverters = new HashMap<>();
    
    protected ClassLoader classloader;
    
    static {
        addElementConverter(new DefinitionsXmlConverter());
        addElementConverter(new DocumentationXmlConverter());
        addElementConverter(new CaseXmlConverter());
        addElementConverter(new PlanModelXmlConverter());
        addElementConverter(new StageXmlConverter());
        addElementConverter(new MilestoneXmlConverter());
        addElementConverter(new TaskXmlConverter());
        addElementConverter(new PlanItemXmlConverter());
        addElementConverter(new SentryXmlConverter());
        addElementConverter(new EntryCriterionXmlConverter());
        addElementConverter(new ExitCriterionXmlConverter());
        addElementConverter(new PlanItemOnPartXmlConverter());
        addElementConverter(new CaseTaskXmlConverter());
        addElementConverter(new ProcessXmlConverter());
        addElementConverter(new ProcessTaskXmlConverter());
        
        addTextConverter(new StandardEventXmlConverter());
    }
    
    public static void addElementConverter(BaseCmmnXmlConverter converter) {
        elementConverters.put(converter.getXMLElementName(), converter);
    }
    
    public static void addTextConverter(BaseCmmnXmlConverter converter) {
        textConverters.put(converter.getXMLElementName(), converter);
    }
    
    public CmmnModel convertToCmmnModel(InputStreamProvider inputStreamProvider) {
        return convertToCmmnModel(inputStreamProvider, true, true);
    }
    
    public CmmnModel convertToCmmnModel(InputStreamProvider inputStreamProvider, boolean validateSchema, boolean enableSafeBpmnXml) {
        return convertToCmmnModel(inputStreamProvider, validateSchema, enableSafeBpmnXml, DEFAULT_ENCODING);
    }
    
    public CmmnModel convertToCmmnModel(InputStreamProvider inputStreamProvider, boolean validateSchema, boolean enableSafeBpmnXml, String encoding) {
        XMLInputFactory xif = XMLInputFactory.newInstance();

        if (xif.isPropertySupported(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES)) {
            xif.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        }
        if (xif.isPropertySupported(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES)) {
            xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        }
        if (xif.isPropertySupported(XMLInputFactory.SUPPORT_DTD)) {
            xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        }
        
        if (encoding == null) {
            encoding = DEFAULT_ENCODING;
        }

        if (validateSchema) {
            try (InputStreamReader in = new InputStreamReader(inputStreamProvider.getInputStream(), encoding)) {
                if (!enableSafeBpmnXml) {
                    validateModel(inputStreamProvider);
                } else {
                    validateModel(xif.createXMLStreamReader(in));
                }
            } catch (UnsupportedEncodingException e) {
                throw new CmmnXMLException("The CMMN 1.1 xml is not properly encoded", e);
            } catch(XMLStreamException e){
                throw new CmmnXMLException("Error while reading the CMMN 1.1 XML", e);
            } catch(Exception e){
                throw new CmmnXMLException(e.getMessage(), e);
            }
        }
        // The input stream is closed after schema validation
        try (InputStreamReader in = new InputStreamReader(inputStreamProvider.getInputStream(), encoding)) {
            // XML conversion
            return convertToCmmnModel(xif.createXMLStreamReader(in));
        } catch (UnsupportedEncodingException e) {
            throw new CmmnXMLException("The CMMN 1.1 xml is not properly encoded", e);
        } catch (XMLStreamException e) {
            throw new CmmnXMLException("Error while reading the CMMN 1.1 XML", e);
        } catch (IOException e) {
            throw new CmmnXMLException(e.getMessage(), e);
        }
    }

    public CmmnModel convertToCmmnModel(XMLStreamReader xtr) {
        
        ConversionHelper conversionHelper = new ConversionHelper();
        conversionHelper.setCmmnModel(new CmmnModel());
        
        try {
            String currentXmlElement = null;
            while (xtr.hasNext()) {
                try {
                    xtr.next();
                } catch (Exception e) {
                    LOGGER.debug("Error reading CMMN XML document", e);
                    throw new CmmnXMLException("Error reading XML", e);
                }

                if (xtr.isStartElement()) {
                    currentXmlElement = xtr.getLocalName();
                    if (elementConverters.containsKey(currentXmlElement)) {
                        elementConverters.get(currentXmlElement).convertToCmmnModel(xtr, conversionHelper);
                    }
                    
                } else if (xtr.isEndElement()) {
                    currentXmlElement = null;
                    conversionHelper.setCurrentCmmnElement(null);
                    
                } else if (xtr.isCharacters() && currentXmlElement != null) {
                    if (textConverters.containsKey(currentXmlElement)) {
                        textConverters.get(currentXmlElement).convertToCmmnModel(xtr, conversionHelper);
                    }
                    
                } 
            }
            
        } catch (CmmnXMLException e) {
            throw e;

        } catch (Exception e) {
            LOGGER.error("Error processing CMMN XML document", e);
            throw new CmmnXMLException("Error processing CMMN XML document", e);
        }
        
        // Post process all elements: add instances where a reference is used
        processCmmnElements(conversionHelper);
        return conversionHelper.getCmmnModel();
    }
    
    public void validateModel(InputStreamProvider inputStreamProvider) throws Exception {
        Schema schema = createSchema();

        Validator validator = schema.newValidator();
        validator.validate(new StreamSource(inputStreamProvider.getInputStream()));
    }

    public void validateModel(XMLStreamReader xmlStreamReader) throws Exception {
        Schema schema = createSchema();

        Validator validator = schema.newValidator();
        validator.validate(new StAXSource(xmlStreamReader));
    }

    protected Schema createSchema() throws SAXException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = null;
        if (classloader != null) {
            schema = factory.newSchema(classloader.getResource(XSD_LOCATION));
        }

        if (schema == null) {
            schema = factory.newSchema(this.getClass().getClassLoader().getResource(XSD_LOCATION));
        }

        if (schema == null) {
            throw new CmmnXMLException("CMND XSD could not be found");
        }
        return schema;
    }
    
    protected void processCmmnElements(ConversionHelper conversionHelper) {
        for (Case caze : conversionHelper.getCmmnModel().getCases()) {
            processPlanFragment(conversionHelper.getCmmnModel(), caze.getPlanModel());
        }
        
        // CMMN doesn't mandate ids on many elements ... adding generated ids 
        // to those elements as this makes the logic much easier
        ensureIds(conversionHelper.getPlanFragments(), "planFragment_");
        ensureIds(conversionHelper.getStages(), "stage_");
        ensureIds(conversionHelper.getEntryCriteria(), "entryCriterion_");
        ensureIds(conversionHelper.getExitCriteria(), "exitCriterion_");
        ensureIds(conversionHelper.getSentries(), "sentry_");
        ensureIds(conversionHelper.getSentryOnParts(), "onPart_");
        ensureIds(conversionHelper.getPlanItems(), "planItem_");
        ensureIds(conversionHelper.getPlanItemDefinitions(), "planItemDefinition_");

        // Now everything has an id, the map of all case elements can be filled
        for (Case caze : conversionHelper.getCmmnModel().getCases()) {
            for (CaseElement caseElement : conversionHelper.getCaseElements().get(caze)) {
                caze.getAllCaseElements().put(caseElement.getId(), caseElement);
            }
        }
    }
    
    protected void processPlanFragment(CmmnModel cmmnModel, PlanFragment planFragment) {
        processPlanItems(cmmnModel, planFragment);
        processSentries(planFragment);
        
        if (planFragment instanceof Stage) {
            Stage stage = (Stage) planFragment;
            for (PlanItemDefinition planItemDefinition : stage.getPlanItemDefinitions()) {
                if (planItemDefinition instanceof PlanFragment) {
                    processPlanFragment(cmmnModel, (PlanFragment) planItemDefinition);
                }
            }
            
            if (!stage.getExitCriteria().isEmpty()) {
                resolveExitCriteriaSentry(stage);
            }
        }
    }

    protected void processPlanItems(CmmnModel cmmnModel, PlanFragment planFragment) {
        for (PlanItem planItem : planFragment.getPlanItems()) {
            
            Stage parentStage = planItem.getParentStage();
            PlanItemDefinition planItemDefinition = parentStage.findPlanItemDefinition(planItem.getDefinitionRef());
            if (planItemDefinition == null) {
                throw new FlowableException("No matching plan item definition found for reference " 
                        + planItem.getDefinitionRef() + " of plan item " + planItem.getId());
            }
            planItem.setPlanItemDefinition(planItemDefinition);
            
            if (!planItem.getEntryCriteria().isEmpty()) {
                resolveEntryCriteria(planItem);
            }
            
            if (!planItem.getExitCriteria().isEmpty()) {
                boolean exitCriteriaAllowed = true;
                if (planItemDefinition instanceof Task) {
                    Task task = (Task) planItemDefinition;
                    if (!task.isBlocking()) {
                        exitCriteriaAllowed = false;
                    }
                }
                
                if (exitCriteriaAllowed) {
                    resolveExitCriteriaSentry((HasExitCriteria) planItem);
                } else {
                    LOGGER.warn("Ignoring exit criteria on plan item " + planItem.getId());
                }
            }
            
            if (planItemDefinition instanceof PlanFragment) {
              processPlanFragment(cmmnModel, (PlanFragment)planItemDefinition );
                
            } else if (planItemDefinition instanceof ProcessTask) {
                ProcessTask processTask = (ProcessTask) planItemDefinition;
                if (processTask.getProcessRef() != null) {
                    org.flowable.cmmn.model.Process process = cmmnModel.getProcessById(processTask.getProcessRef());
                    if (process == null) {
                        throw new FlowableException("Could not find process element for reference " + processTask.getProcessRef());
                    } else {
                        processTask.setProcess(process);
                    }
                }
                
            }
            
        }
    }

    protected void resolveEntryCriteria(HasEntryCriteria hasEntryCriteria) {
        for (Criterion entryCriterion : hasEntryCriteria.getEntryCriteria()) {
            Sentry sentry = entryCriterion.getParent().findSentry(entryCriterion.getSentryRef());
            if (sentry != null) {
                entryCriterion.setSentry(sentry);
            } else {
                throw new FlowableException("No sentry found for reference " 
                        + entryCriterion.getSentryRef() + " of entry criterion " + entryCriterion.getId());
            }
        }
    }
    
    protected void resolveExitCriteriaSentry(HasExitCriteria hasExitCriteria) {
        for (Criterion exitCriterion : hasExitCriteria.getExitCriteria()) {
            Sentry sentry = exitCriterion.getParent().findSentry(exitCriterion.getSentryRef());
            if (sentry != null) {
                exitCriterion.setSentry(sentry);
            } else {
                throw new FlowableException("No sentry found for reference " 
                        + exitCriterion.getSentryRef() + " of exit criterion " + exitCriterion.getId());
            }
        }
    }
    
    protected void processSentries(PlanFragment planFragment) {
        for (Sentry sentry : planFragment.getSentries()) {
            for (SentryOnPart onPart : sentry.getOnParts()) {
                PlanItem planItem = sentry.getParent().findPlanItem(onPart.getSourceRef());
                if (planItem != null) {
                    onPart.setSource(planItem);
                } else {
                    throw new FlowableException("Could not resolve on part source reference " 
                            + onPart.getSourceRef() + " of sentry " + sentry.getId());
                }
            }
        }
    }
    
    protected void ensureIds(List<? extends BaseElement> elements, String idPrefix) {
        Map<String, BaseElement> elementsWithId = new HashMap<>();
        Set<BaseElement> baseElementsWithoutId = new HashSet<>();
        for (BaseElement baseElement : elements) {
            if (baseElement.getId() != null) {
                elementsWithId.put(baseElement.getId(), baseElement);
            } else {
                baseElementsWithoutId.add(baseElement);
            }
        }
        
        if (!baseElementsWithoutId.isEmpty()) {
            int counter = 1;
            for (BaseElement baseElement : baseElementsWithoutId) {
                String id = idPrefix + counter++;
                while (elementsWithId.containsKey(id)) {
                    id = idPrefix + counter++;
                }
                
                baseElement.setId(id);
                elementsWithId.put(id, baseElement);
            }
        }
    }

    public void setClassloader(ClassLoader classloader) {
        this.classloader = classloader;
    }

    public static Map<String, BaseCmmnXmlConverter> getConvertersToCmmnModelMap() {
        return elementConverters;
    }

    public static void setConvertersToCmmnModelMap(Map<String, BaseCmmnXmlConverter> convertersToCmmnModelMap) {
        CmmnXmlConverter.elementConverters = convertersToCmmnModelMap;
    }
    
}