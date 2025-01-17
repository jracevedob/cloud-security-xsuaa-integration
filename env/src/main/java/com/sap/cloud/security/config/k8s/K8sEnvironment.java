/**
 * SPDX-FileCopyrightText: 2018-2022 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.cloud.security.config.k8s;

import com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;
import com.sap.cloud.environment.servicebinding.api.TypedMapView;
import com.sap.cloud.security.config.Environment;
import com.sap.cloud.security.config.OAuth2ServiceConfiguration;
import com.sap.cloud.security.config.OAuth2ServiceConfigurationBuilder;
import com.sap.cloud.security.config.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.sap.cloud.security.config.cf.CFConstants.IAS.DOMAINS;
import static com.sap.cloud.security.config.cf.CFConstants.SERVICE_PLAN;
import static com.sap.cloud.security.config.cf.CFConstants.XSUAA.UAA_DOMAIN;
import static com.sap.cloud.security.config.k8s.K8sConstants.Plan;

/**
 * Loads the OAuth configuration ({@link OAuth2ServiceConfiguration}) of a
 * supported identity {@link Service} in the Kubernetes Environment by accessing
 * defaults service secrets paths "/etc/secrets/sapbtp/xsuaa" for Xsuaa service
 * or "/etc/secrets/sapbtp/identity" for IAS service.
 */
public class K8sEnvironment implements Environment {
	private static final Logger LOGGER = LoggerFactory.getLogger(K8sEnvironment.class);

	static K8sEnvironment instance;
	private final Map<Service, Map<String, OAuth2ServiceConfiguration>> serviceConfigurations;

	private K8sEnvironment() {
		serviceConfigurations = new EnumMap<>(Service.class);
		loadAll();
	}

	public static K8sEnvironment getInstance() {
		if (instance == null) {
			instance = new K8sEnvironment();
		}
		return instance;
	}

	@Nonnull
	@Override
	public Type getType() {
		return Type.KUBERNETES;
	}

	private void loadAll() {
		List<ServiceBinding> serviceBindings = DefaultServiceBindingAccessor.getInstance().getServiceBindings();

		Map<String, OAuth2ServiceConfiguration> xsuaaPlans = serviceBindings.stream()
				.filter(b -> Service.XSUAA.getCFName().equalsIgnoreCase(b.getServiceName().orElse(null)))
				.map(this::mapToOAuth2ServiceConfiguration)
				.collect(Collectors.toMap(config -> config.getProperty(SERVICE_PLAN),
						Function.identity()));
		Map<String, OAuth2ServiceConfiguration> identityPlans = serviceBindings.stream()
				.filter(b -> Service.IAS.getCFName().equalsIgnoreCase(b.getServiceName().orElse(null)))
				.map(this::mapToOAuth2ServiceConfiguration)
				.collect(Collectors.toMap(config -> config.getProperty(SERVICE_PLAN),
						Function.identity()));
		serviceConfigurations.put(Service.XSUAA, xsuaaPlans);
		serviceConfigurations.put(Service.IAS, identityPlans);
	}

	private OAuth2ServiceConfiguration mapToOAuth2ServiceConfiguration(ServiceBinding b) {
		if (!b.getServiceName().isPresent()) {
			LOGGER.error("Ignores Service Binding with name {} as service name is not provided.", b.getName());
			return null; // as of now, method is never called when service name isn't given
		}
		final Service service = Service.from(b.getServiceName().get());
		OAuth2ServiceConfigurationBuilder configBuilder = OAuth2ServiceConfigurationBuilder.forService(service)
				.withProperties(TypedMapView.ofCredentials(b).getEntries(String.class))
				.withProperty(SERVICE_PLAN, b.getServicePlan().orElse(Plan.APPLICATION.name()).toUpperCase());
		switch (service) {
		case XSUAA:
			configBuilder.withProperty(UAA_DOMAIN,
					(String) b.getCredentials().get(UAA_DOMAIN));
			break;
		case IAS:
			List<String> domains = TypedMapView.ofCredentials(b).getListView(DOMAINS).getItems(String.class);
			LOGGER.info("first domain : {}", domains.get(0));
			configBuilder.withDomains(domains.toArray(new String[] {}));
			break;
		}
		return configBuilder.build();
	}

	/**
	 * Loads all configurations of all service instances of the dedicated service.
	 *
	 * @param service
	 *            the service name
	 * @return the list of all found configurations or empty map, in case there are
	 *         no service bindings.
	 */
	Map<String, OAuth2ServiceConfiguration> getServiceConfigurationsOf(Service service) {
		return serviceConfigurations.getOrDefault(service, Collections.emptyMap());
	}

	@Nullable
	@Override
	public OAuth2ServiceConfiguration getXsuaaConfiguration() {
		return Optional.ofNullable(getServiceConfigurationsOf(Service.XSUAA).get(Plan.APPLICATION.name()))
				.orElse(Optional.ofNullable(getServiceConfigurationsOf(Service.XSUAA).get(Plan.BROKER.name()))
						.orElse(Optional.ofNullable(getServiceConfigurationsOf(Service.XSUAA).get(Plan.SPACE.name()))
								.orElse(Optional
										.ofNullable(getServiceConfigurationsOf(Service.XSUAA).get(Plan.DEFAULT.name()))
										.orElse(null))));

	}

	@Nullable
	@Override
	public OAuth2ServiceConfiguration getXsuaaConfigurationForTokenExchange() {
		if (getNumberOfXsuaaConfigurations() > 1) {
			return getServiceConfigurationsOf(Service.XSUAA).get(Plan.BROKER.name());
		}
		return getXsuaaConfiguration();
	}

	@Nullable
	@Override
	public OAuth2ServiceConfiguration getIasConfiguration() {
		if (getServiceConfigurationsOf(Service.IAS).size() > 1) {
			LOGGER.warn("{} IAS bindings found. Using the first one from the list",
					getServiceConfigurationsOf(Service.IAS).size());
		}
		return getServiceConfigurationsOf(Service.IAS).entrySet().stream().findFirst().map(Map.Entry::getValue)
				.orElse(null);
	}

	@Override
	public int getNumberOfXsuaaConfigurations() {
		return getServiceConfigurationsOf(Service.XSUAA).size();
	}

}
