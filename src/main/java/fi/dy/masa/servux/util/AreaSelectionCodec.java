package fi.dy.masa.servux.util;

import javax.annotation.Nullable;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.schematic.selection.AreaSelection;
import fi.dy.masa.servux.util.data.tag.CompoundData;

/**
 * Carries an area selection between the client and the server.
 * <p>
 * The encoding is Litematica's own {@code AreaSelection} JSON, verbatim, as a single
 * string: this class is a direct port of Litematica's, field for field, so its
 * {@code toJson()} output is exactly what {@link AreaSelection#fromJson} here expects.
 * Reusing it means there is no second area format to keep in step - the one the client
 * already writes to disk is the one that goes over the wire.
 */
public class AreaSelectionCodec
{
	/** The {@code CompoundData} key an area travels under. */
	public static final String KEY = "Area";

	public static void write(CompoundData tag, AreaSelection area)
	{
		tag.putString(KEY, area.toJson().toString());
	}

	/**
	 * @return the area, or null if the field is missing or not parseable. This runs off a
	 *         packet, so a malformed payload has to be a null rather than an exception
	 *         escaping into the network thread.
	 */
	@Nullable
	public static AreaSelection read(CompoundData tag)
	{
		String json = tag.getStringOrDefault(KEY, "");

		if (json.isEmpty())
		{
			return null;
		}

		try
		{
			JsonElement element = JsonParser.parseString(json);

			if (!element.isJsonObject())
			{
				return null;
			}

			AreaSelection area = AreaSelection.fromJson(element.getAsJsonObject());

			// An area with no boxes describes no volume at all; treat it as absent rather
			// than starting a walk that would visit nothing and report success
			return area.getAllSubRegionBoxes().isEmpty() ? null : area;
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("AreaSelectionCodec#read(): failed to read the area selection; {}", e.getLocalizedMessage());
			return null;
		}
	}
}
