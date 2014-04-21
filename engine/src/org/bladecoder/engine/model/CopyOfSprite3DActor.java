package org.bladecoder.engine.model;

import java.nio.IntBuffer;

import org.bladecoder.engine.actions.ActionCallback;
import org.bladecoder.engine.anim.EngineTween;
import org.bladecoder.engine.assets.EngineAssetManager;
import org.bladecoder.engine.util.ActionCallbackSerialization;
import org.bladecoder.engine.util.EngineLogger;
import org.bladecoder.engine.util.Utils3D;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.environment.PointLight;
import com.badlogic.gdx.graphics.g3d.model.Animation;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController.AnimationDesc;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController.AnimationListener;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;

public class CopyOfSprite3DActor extends SpriteActor {

	private static int MAX_BONES = 40;
	private static Format FRAMEBUFFER_FORMAT = Format.RGBA4444;

	private String modelFileName;

	private Environment environment;
	private Environment shadowEnvironment;

	private ModelInstance modelInstance;
	private AnimationController controller;

	private FrameBuffer fb = null;

	private int width, height;

	private PerspectiveCamera camera3d = null;

	private Vector3 cameraPos;
	private Vector3 cameraRot;
	private String cameraName = "Camera";
	private float cameraFOV = 49.3f;
	
	// Rotation of the model in the Y axis
	private float modelRotation = 0;

	transient private ModelBatch modelBatch;
	transient private ModelBatch shadowBatch;
	transient private ModelBatch floorBatch;

	/**
	 * CallCb is true when an animation ends and the animationCb must be called
	 * in the next update loop This is necessary because we can't call the
	 * animationCb in the 'animationListener' cause the onEvent method can put
	 * another animation in the same object that is currently finishing.
	 **/
	private boolean callCb = false;
	private ActionCallback animationCb = null;
	private String animationCbSer = null;
	
	transient IntBuffer results = BufferUtils.newIntBuffer(16);
	
	DirectionalShadowLight shadowLight = (DirectionalShadowLight)new DirectionalShadowLight(1024,
			1024, 30f, 30f, 1f, 100f).set(1f, 1f, 1f, 0.01f, -1f, 0.01f);
	
	PointLight celLight;
	
	String celLightName = "Light";

	/**
	 * Render the 3D model into the texture
	 */
	private void renderTex() {
		// GET CURRENT VIEWPORT SIZE
		Gdx.gl20.glGetIntegerv(GL20.GL_VIEWPORT, results);
		Rectangle viewport = new Rectangle(results.get(0), results.get(1),
	    		results.get(2),results.get(3));		

		// GENERATE SHADOW MAP
		shadowLight.begin(Vector3.Zero, camera3d.direction);
		shadowBatch.begin(shadowLight.getCamera());
		shadowBatch.render(modelInstance);
		shadowBatch.end();
		shadowLight.end();

		fb.begin();

		Gdx.gl.glClearColor(0, 0, 0, 0);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

		// DRAW SHADOW
		floorBatch.begin(camera3d);
		floorBatch.render(Utils3D.getFloor(), shadowEnvironment);
		floorBatch.end();

		// DRAW MODEL
		modelBatch.begin(camera3d);

		if (EngineLogger.debugMode()
				&& EngineLogger.debugLevel == EngineLogger.DEBUG1)
			modelBatch.render(Utils3D.getAxes(), environment);

		modelBatch.render(modelInstance, environment);

		modelBatch.end();

		fb.end((int)viewport.x, (int)viewport.y, (int)viewport.width, (int)viewport.height);
	}

	public void setCameraPos(float x, float y, float z) {
		if (cameraPos == null)
			cameraPos = new Vector3(x, y, z);
		else
			cameraPos.set(x, y, z);
	}

	public void setCameraRot(float x, float y, float z) {
		if (cameraRot == null)
			cameraRot = new Vector3(x, y, z);
		else
			cameraRot.set(x, y, z);
	}

	public void setCameraFOV(float fov) {
		this.cameraFOV = fov;
	}

	public void setCameraName(String name) {
		this.cameraName = name;
	}
	
	public void setCelLightName(String name) {
		this.celLightName = name;
	}

	private void createCamera() {
		camera3d = new PerspectiveCamera(cameraFOV, width, height);

		if (cameraPos == null) {
			// SET CAMERA POS FROM MODEL IF EXISTS
			Node n = modelInstance.getNode(cameraName);

			if (n != null) {
				cameraPos = n.translation;
			} else {
				cameraPos = new Vector3(0,0,5);
			}
		}

		if (cameraRot == null) {
			// SET CAMERA ROT FROM MODEL IF EXISTS
			Node n = modelInstance.getNode(cameraName);

			if (n != null) {
				float rx = (float) (MathUtils.radiansToDegrees * Math.asin(2
						* n.rotation.x * n.rotation.y + 2 * n.rotation.z
						* n.rotation.w));
				float ry = (float) (MathUtils.radiansToDegrees * Math.atan2(2
						* n.rotation.x * n.rotation.w - 2 * n.rotation.y
						* n.rotation.z, 1 - 2 * n.rotation.x * n.rotation.x - 2
						* n.rotation.z * n.rotation.z));
				float rz = (float) (Math.atan2(2 * n.rotation.y * n.rotation.w
						- 2 * n.rotation.x * n.rotation.z, 1 - 2 * n.rotation.y
						* n.rotation.y - 2 * n.rotation.z * n.rotation.z));

				setCameraRot(rx, ry, rz);
			} else {
				cameraRot = new Vector3();
			}
		}

		camera3d.position.set(cameraPos);

		camera3d.rotate(cameraRot.x, 1, 0, 0);
		camera3d.rotate(cameraRot.y, 0, 1, 0);
		camera3d.rotate(cameraRot.z, 0, 0, 1);

		camera3d.near = 0.1f;
		camera3d.far = 30;
		camera3d.update();
	}

	private final AnimationListener animationListener = new AnimationListener() {

		@Override
		public void onLoop(AnimationDesc animation) {
		}

		@Override
		public void onEnd(AnimationDesc animation) {
			if (animationCb != null || animationCbSer != null) {
				callCb = true;
			}
		}
	};
	

	public Array<Animation> getAnimations() {
		return modelInstance.animations;
	}
	
	public void startFrameAnimation(String id, int repeatType, int count,
			boolean reverse, ActionCallback cb) {

		if (modelInstance.getAnimation(id) != null) {
			animationCb = cb;
			callCb = false;
			controller.setAnimation(id, count, reverse ? -1 : 1,
					animationListener);
			return;
		}

		int idx = id.indexOf('.');
		if (idx != -1) {
			String s = id.substring(0, idx);
			String dir = id.substring(idx + 1);

			lookat(dir);

			if (modelInstance.getAnimation(s) != null) {
				animationCb = cb;
				callCb = false;
				controller.setAnimation(s, count, reverse ? -1 : 1,
						animationListener);

				return;
			}

			EngineLogger.error("Animation NOT FOUND: " + s + " for actor "
					+ getId());
		}

		// ERROR CASE
		EngineLogger.error("Animation '" + id + "' NOT FOUND for actor "
				+ getId());

		for (Animation a : modelInstance.animations) {
			EngineLogger.debug(a.id);
		}

		if (cb != null)
			cb.onEvent();
	}

	public void setModel(String model) {
		this.modelFileName = model;
	}

	@Override
	public void lookat(String dir) {
		EngineLogger.debug("LOOKAT DIRECTION - " + dir);

		if (dir.equals(BACK))
			lookat(180);
		else if (dir.equals(FRONT))
			lookat(0);
		else if (dir.equals(LEFT))
			lookat(270);
		else if (dir.equals(RIGHT))
			lookat(90);
		else if (dir.equals(BACKLEFT))
			lookat(225);
		else if (dir.equals(BACKRIGHT))
			lookat(135);
		else if (dir.equals(FRONTLEFT))
			lookat(-45);
		else if (dir.equals(FRONTRIGHT))
			lookat(45);
		else
			EngineLogger.error("LOOKAT: Direction not found - " + dir);

	}

	@Override
	public void lookat(Vector2 p) {
		Vector2 tmp = new Vector2(p);
		float angle = tmp.sub(pos).angle() + 90;
		lookat(angle);
	}

	public void lookat(float angle) {
		modelInstance.transform.setToRotation(Vector3.Y, angle);
		modelRotation = angle;
	}

	@Override
	public void stand() {
		startFrameAnimation(STAND_ANIM, EngineTween.NO_REPEAT, 1, false, null);
	}

	@Override
	public void startWalkFA(Vector2 p0, Vector2 pf) {
		lookat(pf);
		startFrameAnimation(WALK_ANIM, EngineTween.REPEAT, -1, false, null);
	}

	@Override
	public String getCurrentFrameAnimationId() {
		return controller.current.animation.id;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer(super.toString());

		sb.append("  Sprite Bbox: ").append(getBBox().toString());

		sb.append("\n  Anims:");

		for (Animation a : modelInstance.animations) {
			sb.append(" ").append(a.id);
		}

		if (controller.current != null)
			sb.append("\n  Current Anim: ").append(
					controller.current.animation.id);

		sb.append("\n");

		return sb.toString();
	}

	public void setSpriteSize(Vector2 size) {
		this.width = (int)size.x;
		this.height = (int)size.y;
	}

	public void update(float delta) {
		if (controller.current != null && controller.current.loopCount != 0) {
			controller.update(delta);
			renderTex();
		}

		if (callCb) {
			callCb = false;

			if(animationCb == null) {
				animationCb = ActionCallbackSerialization.find(animationCbSer);
				animationCbSer = null;
			}
			
			animationCb.onEvent();
		}
		
//		lookat(modelRotation + 1);
//		renderTex();
	}

	private void createEnvirontment() {
		environment = new Environment();
		shadowEnvironment = new Environment();
		// environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.8f,
		// 0.8f, 0.8f, 1f));

		// environment.add(new DirectionalLight().set(1f, 1f, 1f, 1f, -1f,
		// -1f));
		
		if(celLight == null) {
			Node n = modelInstance.getNode(celLightName);
			
			if(n != null) {
				celLight = new PointLight().set(1f, 1f, 1f, n.translation, 1f);
			} else {
				celLight = new PointLight().set(1f, 1f, 1f, 0.5f, 1f, 1f, 1f);
			}
		} 
		
		environment.add(celLight);

		shadowEnvironment.add(shadowLight);
		shadowEnvironment.shadowMap = shadowLight;
	}

	@Override
	public void loadAssets() {
		super.loadAssets();

		EngineAssetManager.getInstance().loadModel3D(modelFileName);
	}

	@Override
	public void retrieveAssets() {
		super.retrieveAssets();
		Model model3d = EngineAssetManager.getInstance().getModel3D(
				modelFileName);
		modelInstance = new ModelInstance(model3d);

		createEnvirontment();
		createCamera();

		// CRETE BATCHS
		Config modelConfigShader = new Config(Gdx.files.classpath(
				"org/bladecoder/engine/shading/cel.vertex.glsl").readString(),
				Gdx.files.classpath(
						"org/bladecoder/engine/shading/cel.fragment.glsl")
						.readString());

		modelConfigShader.numBones = MAX_BONES;
		modelConfigShader.numDirectionalLights = 0;
		modelConfigShader.numPointLights = 1;
		modelConfigShader.numSpotLights = 0;

		modelBatch = new ModelBatch(
				new DefaultShaderProvider(modelConfigShader));

		shadowBatch = new ModelBatch(new DepthShaderProvider());
		floorBatch = new ModelBatch(
				new DefaultShaderProvider(
						Gdx.files
								.classpath("org/bladecoder/engine/shading/cel.vertex.glsl"),
						Gdx.files
								.classpath("org/bladecoder/engine/shading/floor.fragment.glsl")));
		// modelBatch = new ModelBatch(new DefaultShaderProvider() {
		// @Override
		// protected Shader createShader(Renderable renderable) {
		// return new CellShader();
		// }
		// });

		// CREATE FBO
		// EngineLogger.debug("CREATING FRAMEBUFFER: " + width + "x" + height);
		fb = new FrameBuffer(FRAMEBUFFER_FORMAT, width, height, true) {
			@Override
			protected void setupTexture() {
				colorTexture = new Texture(width, height, format);
				colorTexture.setFilter(TextureFilter.Linear,
						TextureFilter.Linear);
				colorTexture.setWrap(TextureWrap.ClampToEdge,
						TextureWrap.ClampToEdge);
			}
		};

		tex = new TextureRegion(fb.getColorBufferTexture());
		tex.flip(false, true);

		// SET ANIMATION
		controller = new AnimationController(modelInstance);

		if (initFrameAnimation != null) {
			startFrameAnimation(initFrameAnimation, null);
		}
		
		lookat(modelRotation);

		renderTex();
	}

	@Override
	public void dispose() {
		super.dispose();

		EngineAssetManager.getInstance().disposeModel3D(modelFileName);
		modelBatch.dispose();
		shadowBatch.dispose();
		floorBatch.dispose();
		fb.dispose();
	}

	@Override
	public void write(Json json) {
		super.write(json);

		json.writeValue("model3d", modelFileName);
		json.writeValue("width", width);
		json.writeValue("height", height);
		json.writeValue("cameraPos", cameraPos, cameraPos == null ? null : Vector3.class);
		json.writeValue("cameraRot", cameraRot, cameraRot == null ? null : Vector3.class);
		json.writeValue("cameraName", cameraName, cameraName == null ? null : String.class);
		json.writeValue("cameraFOV", cameraFOV);
		json.writeValue("modelRotation", modelRotation);
		json.writeValue("callCb", callCb);
		json.writeValue("animationCb", ActionCallbackSerialization.find(animationCb), animationCb == null ? null : String.class);

		// TODO: shadowlight, cel light
	}

	@Override
	public void read(Json json, JsonValue jsonData) {
		super.read(json, jsonData);

		modelFileName = json.readValue("model3d", String.class, jsonData);
		width = json.readValue("width", Integer.class, jsonData);
		height = json.readValue("height", Integer.class, jsonData);
		cameraPos = json.readValue("cameraPos", Vector3.class, jsonData);
		cameraRot = json.readValue("cameraRot", Vector3.class, jsonData);
		cameraName = json.readValue("cameraName", String.class, jsonData);
		cameraFOV = json.readValue("cameraFOV", Float.class, jsonData);
		modelRotation = json.readValue("modelRotation", Float.class, jsonData);
		callCb = json.readValue("callCb", Boolean.class, jsonData);
		animationCbSer = json.readValue("animationCb", String.class, jsonData);
	}

}