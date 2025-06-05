package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.cli.helper.StringPair;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.SwapException;
import io.goobi.api.job.actapro.model.ActaProApi;
import io.goobi.api.job.actapro.model.AuthenticationToken;
import io.goobi.api.job.actapro.model.Document;
import io.goobi.api.job.actapro.model.MetadataMapping;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.exceptions.UGHException;

@PluginImplementation
@Log4j2
public class ActaproStepPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = 194605406994335021L;
    @Getter
    private String title = "intranda_step_actapro";
    @Getter
    private Step step;
    private Process process;

    private String returnPath;

    private String actaProIdFieldName;

    // authentication
    private String authServiceUrl;
    private String authServiceHeader;
    private String authServiceUsername;
    private String authServicePassword;

    private String connectorUrl;

    private List<StringPair> requiredFields;

    private transient List<MetadataMapping> metadataFields;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        process = step.getProzess();
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        actaProIdFieldName = config.getString("actaProIdFieldName", "RecordID");
        try {
            XMLConfiguration actaProConfig = new XMLConfiguration(
                    ConfigurationHelper.getInstance().getConfigurationFolder() + "plugin_intranda_administration_actapro_sync.xml");
            actaProConfig.setListDelimiter('&');
            actaProConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
            actaProConfig.setExpressionEngine(new XPathExpressionEngine());
            authServiceUrl = actaProConfig.getString("/authentication/authServiceUrl");
            authServiceHeader = actaProConfig.getString("/authentication/authServiceHeader");
            authServiceUsername = actaProConfig.getString("/authentication/authServiceUsername");
            authServicePassword = actaProConfig.getString("/authentication/authServicePassword");
            connectorUrl = actaProConfig.getString("/connectorUrl");
        } catch (ConfigurationException e) {
            log.error(e);
        }

        requiredFields = new ArrayList<>();
        List<HierarchicalConfiguration> sub = config.configurationsAt("requiredField");
        for (HierarchicalConfiguration hc : sub) {
            requiredFields.add(new StringPair(hc.getString("@type"), hc.getString(".")));
        }

        metadataFields = new ArrayList<>();

        List<HierarchicalConfiguration> mapping = config.configurationsAt("/field");
        for (HierarchicalConfiguration c : mapping) {
            MetadataMapping mm = new MetadataMapping(c.getString("@type"), c.getString("@groupType", ""), c.getString("@value"),
                    "", "");
            metadataFields.add(mm);
        }

        log.trace("Actapro step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_actapro.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {

        String actaProId = null;
        DigitalDocument digDoc = null;
        try {
            // open metadata file
            Fileformat fileformat = process.readMetadataFile();
            digDoc = fileformat.getDigitalDocument();
            DocStruct logical = digDoc.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                logical = logical.getAllChildren().get(0);
            }

            // find configured field with the actapro id
            for (Metadata md : logical.getAllMetadata()) {
                if (actaProIdFieldName.equals(md.getType().getName())) {
                    actaProId = md.getValue();
                }
            }

            if (StringUtils.isBlank(actaProId)) {
                // abort with error
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "ACTApro update failed, ACTApro ID not found.");
                return PluginReturnValue.ERROR;
            }

            //  check if required properties/metadata are set
            for (StringPair field : requiredFields) {
                boolean fieldExists = false;
                if ("property".equalsIgnoreCase(field.getOne())) {
                    for (GoobiProperty prop : process.getProperties()) {
                        if (prop.getPropertyName().equals(field.getTwo())) {
                            fieldExists = true;
                        }
                    }
                } else {
                    for (Metadata md : logical.getAllMetadata()) {
                        if (field.getTwo().equals(md.getType().getName())) {
                            fieldExists = true;
                        }
                    }
                }

                if (!fieldExists) {
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                            "ACTApro update failed, required field " + field.getTwo() + " does not exist.");
                    return PluginReturnValue.ERROR;
                }
            }

        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
        }

        AuthenticationToken token = null;
        Document doc = null;

        // search for actapro document
        try (Client client = ClientBuilder.newClient()) {
            token = ActaProApi.authenticate(client, authServiceHeader, authServiceUrl, authServiceUsername,
                    authServicePassword);
            doc = ActaProApi.getDocumentByKey(client, token, connectorUrl, actaProId);
            if (doc == null) {
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "ACTApro update failed, no ACTApro document found.");
                return PluginReturnValue.ERROR;
            }
        }

        // add/update fields within document
        VariableReplacer replacer = new VariableReplacer(digDoc, process.getRegelsatz().getPreferences(), process, step);
        for (MetadataMapping mm : metadataFields) {
            String value = replacer.replace(mm.getEadField());
            ActaProApi.updateDocumentField(doc, mm, value);
        }

        // update document in actapro
        try (Client client = ClientBuilder.newClient()) {
            ActaProApi.updateDocument(client, token, connectorUrl, doc);
        }
        return PluginReturnValue.FINISH;
    }

}
