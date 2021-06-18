/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */


package qupath.lib.analysis.heatmaps;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleToIntFunction;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.color.ColorMaps;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.color.ColorMaps.ColorMap;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectPredicates.PathObjectPredicate;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.RoiTools;
import qupath.opencv.ml.pixel.PixelClassifierTools;
import qupath.opencv.ml.pixel.PixelClassifiers;

/**
 * Class for constructing and using density maps.
 * 
 * @author Pete Bankhead
 */
public class DensityMaps {
	
	private final static Logger logger = LoggerFactory.getLogger(DensityMaps.class);
	
	/**
	 * Channel name for the channel with all object counts (not always present).
	 */
	public final static String CHANNEL_ALL_OBJECTS = "Counts";
	
	private static final PathClass DEFAULT_HOTSPOT_CLASS = PathClassFactory.getPathClass("Hotspot", ColorTools.packRGB(200, 120, 20));

	private static int preferredTileSize = 2048;
	
	
	/**
	 * Density map types.
	 */
	public static enum DensityMapType {
		
		/**
		 * No normalization; maps provide raw object counts in a defined radius.
		 * This is equivalent to applying a circular sum filter to object counts per pixel.
		 */
		SUM,
		
		/**
		 * Gaussian-weighted area normalization; maps provide weighted averaged object counts in a defined radius.
		 * This is equivalent to applying a Gaussian filter to object counts per pixel.
		 */
		GAUSSIAN,
		
		/**
		 * Object normalization; maps provide local proportions of objects (e.g. may be used to get a Positive %)
		 */
		PERCENT;
		
		@Override
		public String toString() {
			switch(this) {
			case SUM:
				return "By area (raw counts)";
			case PERCENT:
				return "Objects %";
			case GAUSSIAN:
				return "Gaussian-weighted";
			default:
				throw new IllegalArgumentException("Unknown enum " + this);
			}
		}
		
	}
	
	
	/**
	 * Create a new {@link DensityMapBuilder} to create a customized density map.
	 * @param allObjects predicate to identify which objects will be included in the density map
	 * @return the builder
	 */
	public static DensityMapBuilder builder(PathObjectPredicate allObjects) {
		return new DensityMapBuilder(allObjects);
	}
	
	/**
	 * Create a new {@link DensityMapBuilder} initialized with the same properties as an existing builder.
	 * @param builder the existing builder
	 * @return the new builder
	 */
	public static DensityMapBuilder builder(DensityMapBuilder builder) {
		return new DensityMapBuilder(builder.params);
	}
	
	
	
	/**
	 * Helper class for storing parameters to build a {@link ImageServer} representing a density map.
	 */
	public static class DensityMapParameters {
		
		private PixelCalibration pixelSize = null;
		
		private int maxWidth = 1536;
		private int maxHeight = maxWidth;
		
		private double radius = 0;
		private DensityMapType densityType = DensityMapType.SUM;

		private PathObjectPredicate allObjectsFilter;
		private Map<String, PathObjectPredicate> densityFilters = new LinkedHashMap<>();
		
		private DensityMapParameters() {}
		
		private DensityMapParameters(DensityMapParameters params) {
			this.pixelSize = params.pixelSize;
			this.radius = params.radius;
			this.densityType = params.densityType;
			this.maxWidth = params.maxWidth;
			this.maxHeight = params.maxHeight;
			
			this.allObjectsFilter = params.allObjectsFilter;
			this.densityFilters = new LinkedHashMap<>(params.densityFilters);
		}
		
		public double getRadius() {
			return radius;
		}
		
		public PixelCalibration getPixelSize() {
			return pixelSize;
		}
		
		public DensityMapType getDensityType() {
			return densityType;
		}
		
		public PathObjectPredicate getAllObjectsFilter() {
			return allObjectsFilter;
		}
		
		public Map<String, PathObjectPredicate> getDensityFilters() {
			return Collections.unmodifiableMap(densityFilters);
		}
		
	}
	
	
	/**
	 * Builder for an {@link ImageServer} representing a density map or for {@link DensityMapParameters}.
	 */
	public static class DensityMapBuilder {
		
		private DensityMapParameters params = null;
		private ColorModelBuilder colorModelBuilder = null;
		
		private DensityMapBuilder(DensityMapParameters params) {
			Objects.nonNull(params);
			this.params = new DensityMapParameters(params);
		}
		
		private DensityMapBuilder(PathObjectPredicate allObjects) {
			Objects.nonNull(allObjects);
			params = new DensityMapParameters();
			params.allObjectsFilter = allObjects;
		}
		
		/**
		 * Requested pixel size to determine the resolution of the density map, in calibrated units.
		 * <p>
		 * If this is not specified, an {@link ImageData} should be provided to {@link #buildClassifier(ImageData)} 
		 * and used to determine a suitable pixel size based upon the radius value and the image dimensions.
		 * <p>
		 * This is recommended, since specifying a pixel size could potentially result in creating maps 
		 * that are too large or too small, causing performance or memory problems.
		 * 
		 * @param requestedPixelSize
		 * @return this builder
		 * @see #radius(double)
		 */
		public DensityMapBuilder pixelSize(PixelCalibration requestedPixelSize) {
			params.pixelSize = requestedPixelSize;
			return this;
		}
		
		/**
		 * The type of the density map, which determines any associated normalization.
		 * @param type
		 * @return this builder
		 */
		public DensityMapBuilder type(DensityMapType type) {
			params.densityType = type;
			return this;
		}
		
		/**
		 * Add a filter for computing densities.
		 * This is added on top of the filter specified in {@link DensityMaps#builder(PathObjectPredicate)} to 
		 * extract a subset of objects for which densities are determined.
		 * 
		 * @param name name of the filter; usually this is the name of a classification that the objects should have
		 * @param filter the filter itself (predicate that must be JSON-serializable)
		 * @return this builder
		 */
		public DensityMapBuilder addDensities(String name, PathObjectPredicate filter) {
			params.densityFilters.put(name, filter);
			return this;
		}
			
		/**
		 * Set a {@link ColorModelBuilder} that can be used in conjunction with {@link #buildServer(ImageData)}.
		 * If this is not set, the default {@link ColorModel} used with {@link #buildServer(ImageData)} may not 
		 * convert well to RGB.
		 * @param colorModelBuilder
		 * @return
		 */
		public DensityMapBuilder colorModel(ColorModelBuilder colorModelBuilder) {
			this.colorModelBuilder = colorModelBuilder;
			return this;
		}
		
		
		/**
		 * The radius of the filter used to calculate densities.
		 * @param radius
		 * @return this builder
		 */
		public DensityMapBuilder radius(double radius) {
			params.radius = radius;
			return this;
		}
		
		/**
		 * Build a {@link DensityMapParameters} object containing the main density map parameters.
		 * @return
		 */
		public DensityMapParameters buildParameters() {
			return new DensityMapParameters(params);
		}
		
		
		/**
		 * Build a {@link ImageServer} for a density map using the current parameters and the specified {@link ImageData}.
		 * @param imageData
		 * @return the density map
		 * @see #buildServer(ImageData)
		 */
		public PixelClassifier buildClassifier(ImageData<BufferedImage> imageData) {
			return createClassifier(imageData, params);
		}
		
		/**
		 * Build an {@link ImageServer} representing this density map.
		 * <p>
		 * Note that this involved generating a unique ID and caching all tiles.
		 * The reason is that density maps can change over time as the object hierarchy changes, 
		 * and therefore one should be generated that represents a snapshot in time.
		 * However, this imposes a limit on the size of density map that can be generated to 
		 * avoid memory errors.
		 * @param imageData
		 * @return
		 * @see #buildClassifier(ImageData)
		 */
		public ImageServer<BufferedImage> buildServer(ImageData<BufferedImage> imageData) {
			var classifier = createClassifier(imageData, params);
			
			var id = UUID.randomUUID().toString();
			var sb = new StringBuilder();
			sb.append("Density map (radius=");
			sb.append(params.radius);
			sb.append(")-");
			sb.append(imageData.getServerPath());
			sb.append("-");
			sb.append(id);
			
			var colorModel = colorModelBuilder == null ? null : colorModelBuilder.build();
			return PixelClassifierTools.createPixelClassificationServer(imageData, classifier, sb.toString(), colorModel, true);
		}
		
	}
	
	
	public static interface ColorModelBuilder {
		
		public ColorModel build();
		
	}
	
	public static ColorModelBuilder createColorModelBuilder(DisplayBand mainChannel, DisplayBand alphaChannel) {
		return new SingleChannelColorModelBuilder(mainChannel, alphaChannel);
	}
	
	public static DisplayBand createBand(String colorMapName, int band, double minDisplay, double maxDisplay) {
		return createBand(colorMapName, band, minDisplay, maxDisplay, 1);
	}
	
	public static DisplayBand createBand(String colorMapName, int band, double minDisplay, double maxDisplay, double gamma) {
		return new DisplayBand(colorMapName, null, band, minDisplay, maxDisplay, gamma);
	}
	
	static class DisplayBand {
		
		private String colorMapName;
		private ColorMap colorMap;
		
		private int band;
		private double minDisplay;
		private double maxDisplay;
		private double gamma = 1;
		
		private DisplayBand(String colorMapName, ColorMap colorMap, int band, double minDisplay, double maxDisplay, double gamma) {
			this.colorMapName = colorMapName;
			this.colorMap = colorMap;
			this.band = band;
			this.minDisplay = minDisplay;
			this.maxDisplay = maxDisplay;
			this.gamma = gamma;
		}
		
		public ColorMap getColorMap() {
			if (colorMap != null)
				return colorMap;
			else if (colorMapName != null)
				return ColorMaps.getColorMaps().getOrDefault(colorMapName, null);
			else
				return null;
		}
		
	}
	
	static class SingleChannelColorModelBuilder implements ColorModelBuilder {
		
		private DisplayBand band;
		private DisplayBand alphaBand;
		
		private SingleChannelColorModelBuilder(DisplayBand band, DisplayBand alphaBand) {
			Objects.nonNull(band);
			this.band = band;
			this.alphaBand = alphaBand;
		}
		
		@Override
		public ColorModel build() {
			var map = band.getColorMap();
			if (map == null)
				map = ColorMaps.getDefaultColorMap();
			if (band.gamma != 1)
				map = ColorMaps.gammaColorMap(map, band.gamma);
			
			int alphaBandInd = -1;
			double alphaMin = 0;
			double alphaMax = 1;
			double alphaGamma = -1;
			if (alphaBand != null) {
				alphaBandInd = alphaBand.band;
				alphaMin = alphaBand.minDisplay;
				alphaMax = alphaBand.maxDisplay;
				alphaGamma = alphaBand.gamma;				
			}
			return buildColorModel(map, band.band, band.minDisplay, band.maxDisplay, alphaBandInd, alphaMin, alphaMax, alphaGamma);
		}
		
	}
	
	
	
	private static ColorModel buildColorModel(ColorMap colorMap, int band, double minDisplay, double maxDisplay, int alphaCountBand, double minAlpha, double maxAlpha, double alphaGamma) {
		DoubleToIntFunction alphaFun = null;
		if (alphaCountBand < 0) {
			if (alphaGamma <= 0) {
				alphaFun = null;
				alphaCountBand = -1;
			} else {
				alphaFun = ColorModelFactory.createGammaFunction(alphaGamma, minAlpha, maxAlpha);
				alphaCountBand = 0;
			}
		} else if (alphaFun == null) {
			if (alphaGamma < 0)
				alphaFun = d -> 255;
			else if (alphaGamma == 0)
				alphaFun = d -> d > minAlpha ? 255 : 0;
			else
				alphaFun = ColorModelFactory.createGammaFunction(alphaGamma, minAlpha, maxAlpha);
		}
		return ColorModelFactory.createColorModel(PixelType.FLOAT32, colorMap, band, minDisplay, maxDisplay, alphaCountBand, alphaFun);
	}
	
	
	private static PixelClassifier createClassifier(ImageData<BufferedImage> imageData, DensityMapParameters params) {
		
		var pixelSize = params.pixelSize;
		if (pixelSize == null) {
			if (imageData == null) {
				throw new IllegalArgumentException("You need to specify a pixel size or provide an ImageData to generate a density map!");
			}
			var cal = imageData.getServer().getPixelCalibration();
			double radius = params.radius;
			var server = imageData.getServer();
			
			double maxDownsample = Math.round(
					Math.max(
							server.getWidth() / (double)params.maxWidth,
							server.getHeight() / (double)params.maxHeight
							)
					);
			
			if (maxDownsample < 1)
				maxDownsample = 1;
			
			var minPixelSize = cal.createScaledInstance(maxDownsample, maxDownsample);
			if (radius > 0) {
				double radiusPixels = radius / cal.getAveragedPixelSize().doubleValue();
				double radiusDownsample = Math.round(radiusPixels / 10);
				pixelSize = cal.createScaledInstance(radiusDownsample, radiusDownsample);
			}
			if (pixelSize == null || pixelSize.getAveragedPixelSize().doubleValue() < minPixelSize.getAveragedPixelSize().doubleValue())
				pixelSize = minPixelSize;
		}
		
		int radiusInt = (int)Math.round(params.radius / pixelSize.getAveragedPixelSize().doubleValue());
		logger.debug("Creating classiier with pixel size {}, radius = {}", pixelSize, radiusInt);
					    
		var dataOp = new DensityMapDataOp(
		        radiusInt,
		        params.densityFilters,
		        params.allObjectsFilter,
		        params.densityType
		);
		
		var metadata = new PixelClassifierMetadata.Builder()
			    .inputShape(preferredTileSize, preferredTileSize)
			    .inputResolution(pixelSize)
			    .setChannelType(ImageServerMetadata.ChannelType.DENSITY)
			    .outputChannels(dataOp.getChannels())
			    .build();

		return PixelClassifiers.createClassifier(dataOp, metadata);
	}
	
	
	
	public static PathClass getHotspotClass(PathClass baseClass) {
		PathClass hotspotClass = DEFAULT_HOTSPOT_CLASS;
		if (PathClassTools.isValidClass(baseClass)) {
			hotspotClass = PathClassTools.mergeClasses(baseClass, hotspotClass);
		}
		return hotspotClass;
	}
	
	
	public static void traceContours(PathObjectHierarchy hierarchy, ImageServer<BufferedImage> server, int channel, Collection<? extends PathObject> parents, double threshold, boolean doSplit, PathClass hotspotClass) {
		// Get the selected objects
		boolean changes = false;
		if (hotspotClass == null)
			hotspotClass = getHotspotClass(null);
		for (var parent : parents) {
			var annotations = new ArrayList<PathObject>();
			var roiParent = parent.getROI();
			
			var request = roiParent == null ? RegionRequest.createInstance(server) :
				RegionRequest.createInstance(server.getPath(), server.getDownsampleForResolution(0), roiParent);
//						boolean doErode = params.getBooleanParameterValue("erode");
			BufferedImage img;
			try {
				img = server.readBufferedImage(request);					
			} catch (IOException e) {
				logger.error(e.getLocalizedMessage(), e);
				continue;
			}
			if (img == null)
				continue;
			
			var geometry = ContourTracing.createTracedGeometry(img.getRaster(), threshold, Double.POSITIVE_INFINITY, channel, request);
			if (geometry == null || geometry.isEmpty()) {
				continue;
			}
			
			Geometry geomNew = null;
			if (roiParent == null)
				geomNew = geometry;
			else
				geomNew = GeometryTools.ensurePolygonal(geometry.intersection(roiParent.getGeometry()));
			if (geomNew.isEmpty())
				continue;
			
			var roi = GeometryTools.geometryToROI(geomNew, request == null ? ImagePlane.getDefaultPlane() : request.getPlane());
				
			if (doSplit) {
				for (var r : RoiTools.splitROI(roi))
					annotations.add(PathObjects.createAnnotationObject(r, hotspotClass));
			} else
				annotations.add(PathObjects.createAnnotationObject(roi, hotspotClass));
			parent.addPathObjects(annotations);
			changes = true;
		}
		
		if (changes)
			hierarchy.fireHierarchyChangedEvent(DensityMaps.class);
		else
			logger.warn("No thresholded hotspots found!");
		
	}
	
	
	private static SimpleImage toSimpleImage(Raster raster, int band) {
		int w = raster.getWidth();
		int h = raster.getHeight();
		float[] pixels = raster.getSamples(0, 0, w, h, band, (float[])null);
		return SimpleImages.createFloatImage(pixels, w, h);
	}
	
	
	private static int getCountsChannel(ImageServer<BufferedImage> server) {
		if (server.getChannel(server.nChannels()-1).getName().equals(CHANNEL_ALL_OBJECTS))
			return server.nChannels()-1;
		return -1;
	}
	
	
	public static void findHotspots(PathObjectHierarchy hierarchy, ImageServer<BufferedImage> server, int channel, Collection<? extends PathObject> parents, int nHotspots, double radius, double minCount, boolean allowOverlapping, PathClass hotspotClass) throws IOException {
		SimpleImage mask = null;
		int countChannel = getCountsChannel(server);
		
		for (var parent : parents) {
			
			RegionRequest request;
			if (parent.hasROI())
				request = RegionRequest.createInstance(server.getPath(), server.getDownsampleForResolution(0), parent.getROI());
			else
				request = RegionRequest.createInstance(server);
			
			var img = server.readBufferedImage(request);
			var raster = img.getRaster();
			
			var image = toSimpleImage(raster, channel);
			
			if (minCount > 0) {
				mask = countChannel >= 0 ? toSimpleImage(raster, countChannel) : image;
				mask = SimpleProcessing.threshold(mask, minCount, 0, 1, 1);
			}
			
			var finder = new SimpleProcessing.PeakFinder(image)
					.region(request)
					.calibration(server.getPixelCalibration())
					.peakClass(hotspotClass)
					.minimumSeparation(allowOverlapping ? -1 : radius * 2)
					.withinROI(parent.hasROI())
					.radius(radius);
			
			if (mask != null)
				finder.mask(mask);
			
			
			var hotspots = finder.createObjects(parent.getROI(), nHotspots);
			parent.addPathObjects(hotspots);
		}
		
		hierarchy.fireHierarchyChangedEvent(DensityMaps.class);
	}
	
	

}
