/**
 * Copyright (c) 2012-2013 Edgar Espina
 *
 * This file is part of Handlebars.java.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jknack.handlebars;

import static org.apache.commons.lang3.Validate.notEmpty;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.math.NumberUtils;

import com.github.jknack.handlebars.io.TemplateSource;

/**
 * Mustache/Handlebars are contextual template engines. This class represent the
 * 'context stack' of a template.
 * <ul>
 * <li>Objects and hashes should be pushed onto the context stack.
 * <li>All elements on the context stack should be accessible.
 * <li>Multiple sections per template should be permitted.
 * <li>Failed context lookups should be considered falsy.
 * <li>Dotted names should be valid for Section tags.
 * <li>Dotted names that cannot be resolved should be considered falsy.
 * <li>Dotted Names - Context Precedence: Dotted names should be resolved against former
 * resolutions.
 * </ul>
 *
 * @author edgar.espina
 * @since 0.1.0
 */
public class Context {

  /** NOOP ctx. */
  private static final Context NOOP = new Context(null) {
    @Override
    public Object get(final String key) {
      return null;
    }

    @Override
    protected Object get(final String key, final List<String> path) {
      return null;
    }
  };

  /**
   * Special scope for silly block param rules, implemented by handlebars.js. This context will
   * check for pathed variable and resolve them against parent context.
   *
   * @author edgar
   * @since 3.0.0
   */
  private static class BlockParam extends Context {

    /**
     * A new {@link BlockParam}.
     *
     * @param parent Parent context.
     * @param hash A hash model.
     */
    protected BlockParam(final Context parent, final Map<String, Object> hash) {
      super(hash);
      this.extendedContext = new Context(new HashMap<String, Object>());
      this.parent = parent;
      this.data = parent.data;
      this.resolver = parent.resolver;
    }

    @Override
    public Object get(final String key) {
      // path variable should resolve from parent :S
      if (key.startsWith(".")) {
        return parent.get(key);
      }
      return super.get(key);
    }

    @Override
    protected Context newChildContext(final Object model) {
      return new ParentFirst(model);
    }
  }

  /**
   * Context that resolve variables against parent, or fallback to default/normal lookup.
   *
   * @author edgar
   * @since 3.0.0
   */
  private static class ParentFirst extends Context {

    /**
     * Parent first lookup.
     *
     * @param model A model.
     */
    protected ParentFirst(final Object model) {
      super(model);
    }

    @Override
    public Object get(final String key) {
      Object value = parent.get(key);
      if (value == null) {
        return super.get(key);
      }
      return value;
    }

    @Override
    protected Context newChildContext(final Object model) {
      return new ParentFirst(model);
    }
  }

  /**
   * Handlebars and Mustache path separator.
   */
  private static final String PATH_SEPARATOR = "./";

  /**
   * Handlebars 'parent' attribute reference.
   */
  private static final String PARENT_ATTR = "../";

  /**
   * Handlebars 'parent' attribute reference.
   */
  private static final String PARENT = "..";

  /**
   * Handlebars 'this' reference.
   */
  private static final String THIS = "this";

  /**
   * The mustache 'this' reference.
   */
  private static final String MUSTACHE_THIS = ".";

  /**
   * A composite value resolver. It delegate the value resolution.
   *
   * @author edgar.espina
   * @since 0.1.1
   */
  private static class CompositeValueResolver implements ValueResolver {

    /**
     * The internal value resolvers.
     */
    private ValueResolver[] resolvers;

    /**
     * Creates a new {@link CompositeValueResolver}.
     *
     * @param resolvers The value resolvers.
     */
    public CompositeValueResolver(final ValueResolver... resolvers) {
      this.resolvers = resolvers;
    }

    @Override
    public Object resolve(final Object context, final String name) {
      for (ValueResolver resolver : resolvers) {
        Object value = resolver.resolve(context, name);
        if (value != UNRESOLVED) {
          return value == null ? NULL : value;
        }
      }
      return null;
    }

    @Override
    public Object resolve(final Object context) {
      for (ValueResolver resolver : resolvers) {
        Object value = resolver.resolve(context);
        if (value != UNRESOLVED) {
          return value == null ? NULL : value;
        }
      }
      return null;
    }

    @Override
    public Set<Entry<String, Object>> propertySet(final Object context) {
      Set<Entry<String, Object>> propertySet = new LinkedHashSet<Map.Entry<String, Object>>();
      for (ValueResolver resolver : resolvers) {
        propertySet.addAll(resolver.propertySet(context));
      }
      return propertySet;
    }
  }

  /**
   * A context builder.
   *
   * @author edgar.espina
   * @since 0.1.1
   */
  public static final class Builder {

    /**
     * The context product.
     */
    private Context context;

    /**
     * Creates a new context builder.
     *
     * @param parent The parent context. Required.
     * @param model The model data.
     */
    private Builder(final Context parent, final Object model) {
      context = parent.newChild(model);
    }

    /**
     * Creates a new context builder.
     *
     * @param model The model data.
     */
    private Builder(final Object model) {
      context = Context.root(model);
    }

    /**
     * Combine the given model using the specified name.
     *
     * @param name The variable's name. Required.
     * @param model The model data.
     * @return This builder.
     */
    public Builder combine(final String name, final Object model) {
      context.combine(name, model);
      return this;
    }

    /**
     * Combine all the map entries into the context stack.
     *
     * @param model The model data.
     * @return This builder.
     */
    public Builder combine(final Map<String, ?> model) {
      context.combine(model);
      return this;
    }

    /**
     * Set the value resolvers to use.
     *
     * @param resolvers The value resolvers. Required.
     * @return This builder.
     */
    public Builder resolver(final ValueResolver... resolvers) {
      notEmpty(resolvers, "At least one value-resolver must be present.");
      context.setResolver(new CompositeValueResolver(resolvers));
      return this;
    }

    /**
     * Build a context stack.
     *
     * @return A new context stack.
     */
    public Context build() {
      if (context.resolver == null) {
        if (context.parent != NOOP) {
          // Set resolver from parent.
          context.resolver = context.parent.resolver;
        } else {
          // Set default value resolvers: Java Bean like and Map resolvers.
          context.setResolver(
              new CompositeValueResolver(ValueResolver.VALUE_RESOLVERS));
        }
        // Expand resolver to the extended context.
        if (context.extendedContext != NOOP) {
          context.extendedContext.resolver = context.resolver;
        }
      }
      return context;
    }
  }

  /**
   * Mark for fail context lookup.
   */
  private static final Object NULL = new Object();

  /**
   * Parser for path expressions.
   */
  private static final PropertyPathParser PATH_PARSER = new PropertyPathParser(PATH_SEPARATOR);

  /**
   * The qualified name for partials. Internal use.
   */
  public static final String PARTIALS = Context.class.getName() + "#partials";

  /**
   * Inline partials.
   */
  public static final String INLINE_PARTIALS = "__inline_partials_";

  /**
   * The qualified name for partials. Internal use.
   */
  public static final String INVOCATION_STACK = Context.class.getName() + "#invocationStack";

  /**
   * Number of parameters of a helper. Internal use.
   */
  public static final String PARAM_SIZE = Context.class.getName() + "#paramSize";

  /**
   * The parent context. Optional.
   */
  protected Context parent;

  /**
   * The target value. Resolved as '.' or 'this' inside templates. Required.
   */
  Object model;

  /**
   * A thread safe storage.
   */
  protected Map<String, Object> data;

  /**
   * Additional, data can be stored here.
   */
  protected Context extendedContext;

  /**
   * The value resolver.
   */
  protected ValueResolver resolver;

  /**
   * Creates a new context.
   *
   * @param model The target value. Resolved as '.' or 'this' inside templates. Required.
   */
  protected Context(final Object model) {
    this.model = model;
    this.extendedContext = NOOP;
    this.parent = NOOP;
  }

  /**
   * Creates a root context.
   *
   * @param model The target value. Resolved as '.' or 'this' inside
   *        templates. Required.
   * @return A root context.
   */
  private static Context root(final Object model) {
    Context root = new Context(model);
    root.extendedContext = new Context(new HashMap<String, Object>());
    root.data = new HashMap<String, Object>();
    root.data.put(PARTIALS, new HashMap<String, Template>());
    root.data.put(INLINE_PARTIALS, new HashMap<String, Template>());
    root.data.put(INVOCATION_STACK, new LinkedList<TemplateSource>());
    root.data.put("root", model);
    return root;
  }

  /**
   * Insert a new attribute in the context-stack.
   *
   * @param name The attribute's name. Required.
   * @param model The model data.
   * @return This context.
   */
  @SuppressWarnings({"unchecked" })
  public Context combine(final String name, final Object model) {
    Map<String, Object> map = (Map<String, Object>) extendedContext.model;
    map.put(name, model);
    return this;
  }

  /**
   * Insert all the attributes in the context-stack.
   *
   * @param model The model attributes.
   * @return This context.
   */
  @SuppressWarnings({"unchecked" })
  public Context combine(final Map<String, ?> model) {
    Map<String, Object> map = (Map<String, Object>) extendedContext.model;
    map.putAll(model);
    return this;
  }

  /**
   * Read the attribute from the data storage.
   *
   * @param name The attribute's name.
   * @param <T> Data type.
   * @return The attribute value or null.
   */
  @SuppressWarnings("unchecked")
  public <T> T data(final String name) {
    return (T) data.get(name);
  }

  /**
   * Set an attribute in the data storage.
   *
   * @param name The attribute's name. Required.
   * @param value The attribute's value. Required.
   * @return This context.
   */
  public Context data(final String name, final Object value) {
    data.put(name, value);
    return this;
  }

  /**
   * Store the map in the data storage.
   *
   * @param attributes The attributes to add. Required.
   * @return This context.
   */
  public Context data(final Map<String, ?> attributes) {
    data.putAll(attributes);
    return this;
  }

  /**
   * Resolved as '.' or 'this' inside templates.
   *
   * @return The model or data.
   */
  public Object model() {
    return model;
  }

  /**
   * The parent context or null.
   *
   * @return The parent context or null.
   */
  public Context parent() {
    return parent;
  }

  /**
   * List all the properties and values for the given object.
   *
   * @param context The context object.
   * @return All the properties and values for the given object.
   */
  public Set<Entry<String, Object>> propertySet(final Object context) {
    if (context == null) {
      return Collections.emptySet();
    }
    if (context instanceof Context) {
      return resolver.propertySet(((Context) context).model);
    }
    return resolver.propertySet(context);
  }

  /**
   * List all the properties and values of {@link #model()}.
   *
   * @return All the properties and values of {@link #model()}.
   */
  public Set<Entry<String, Object>> propertySet() {
    return propertySet(model);
  }

  /**
   * @return True, if this context is a block param context.
   */
  public boolean isBlockParams() {
    return this instanceof BlockParam;
  }

  /**
   * Lookup the given key inside the context stack.
   * <ul>
   * <li>Objects and hashes should be pushed onto the context stack.
   * <li>All elements on the context stack should be accessible.
   * <li>Multiple sections per template should be permitted.
   * <li>Failed context lookups should be considered falsey.
   * <li>Dotted names should be valid for Section tags.
   * <li>Dotted names that cannot be resolved should be considered falsey.
   * <li>Dotted Names - Context Precedence: Dotted names should be resolved against former
   * resolutions.
   * </ul>
   *
   * @param key The object key.
   * @return The value associated to the given key or <code>null</code> if no value is found.
   */
  public Object get(final String key) {
    // '.' or 'this'
    if (MUSTACHE_THIS.equals(key) || THIS.equals(key)) {
      return internalGet(model);
    }

    // '..' or '../'
    if (key.startsWith(PARENT)) {
      if (parent == null) {
        return null;
      }
      return key.length() == PARENT.length()
          ? internalGet(parent.model)
          : parent.get(key.substring(PARENT_ATTR.length()));
    }

    return get(key, PATH_PARSER.parsePath(key));
  }

  /**
   * Lookup a key/path.
   *
   * @param key Key.
   * @param path Key as path.
   * @return Value.
   */
  protected Object get(final String key, final List<String> path) {
    Object value = internalGet(path);
    if (value == null) {
      // No luck, check the extended context.
      value = extendedContext.get(key, path);
      // No luck, check the data context.
      if (value == null && data != null) {
        String dataKey = key.charAt(0) == '@' ? key.substring(1) : key;
        // simple data keys will be resolved immediately, complex keys need to go down and use a
        // new context.
        value = data.get(dataKey);
        if (value == null && path.size() > 1) {
          // for complex keys, a new data context need to be created per invocation,
          // bc data might changes per execution.
          Context dataContext = Context.newBuilder(data)
              .resolver(this.resolver)
              .build();
          // don't extend the lookup further.
          dataContext.data = null;
          value = dataContext.get(dataKey);
          // destroy it!
          dataContext.destroy();
        }
      }
      // No luck, but before checking at the parent scope we need to check for
      // the 'this' qualifier. If present, no look up will be done.
      if (value == null && !path.get(0).equals(THIS)) {
        value = parent.get(key, path);
      }
    }
    return value == NULL ? null : value;
  }

  /**
   * @param candidate resolve a candidate object.
   * @return A resolved value or the current value if there isn't a resolved value.
   */
  private Object internalGet(final Object candidate) {
    Object resolved = resolver.resolve(candidate);
    return resolved == null ? candidate : resolved;
  }

  /**
   * Iterate over the qualified path and return a value. The value can be
   * null, {@link #NULL} or not null. If the value is <code>null</code>, the
   * value isn't present and the lookup algorithm will searchin for the value in
   * the parent context.
   * If the value is {@value #NULL} the search must stop bc the context for
   * the given path exists but there isn't a value there.
   *
   * @param path The qualified path.
   * @return The value inside the stack for the given path.
   */
  private Object internalGet(final List<String> path) {
    Object current = model;
    // Resolve 'this' to the current model.
    int start = path.get(0).equals(THIS) ? 1 : 0;
    int psize = path.size();
    for (int i = start; i < psize - 1; i++) {
      current = resolve(current, path.get(i));
      if (current == null) {
        return null;
      }
    }
    String name = path.get(psize - 1);
    Object value = resolve(current, name);
    if (value == null && current != model) {
      // We're looking in the right scope, but the value isn't there
      // returns a custom mark to stop looking
      value = NULL;
    }
    return value;
  }

  /**
   * Do the actual lookup of an unqualified property name.
   *
   * @param current The target object.
   * @param expression The access expression.
   * @return The associated value.
   */
  private Object resolve(final Object current, final String expression) {
    // Null => null
    if (current == null) {
      return null;
    }

    Object result = resolver.resolve(current, expression);
    if (result != null && result != ValueResolver.UNRESOLVED) {
      // no need to look for complex path expression, we already found the value.
      return result;
    }

    // nothing was found, let's test if we have an invalid identifier.
    // array/list access or invalid Java identifiers wrapped with []
    if (expression.charAt(0) == '[' && expression.charAt(expression.length() - 1) == ']') {
      String idx = expression.substring(1, expression.length() - 1);
      if (NumberUtils.isDigits(idx)) {
        result = resolveArrayAccess(current, idx);
        if (result != NULL) {
          return result;
        }
      }
      // It is not a index base object, defaults to string property lookup
      // (usually not a valid Java identifier)
      return resolver.resolve(current, idx);
    }
    // array or list access, exclusive
    if (NumberUtils.isDigits(expression)) {
      result = resolveArrayAccess(current, expression);
      if (result != NULL) {
        return result;
      }
    }
    return result;
  }

  /**
   * Resolve a array or list access using idx.
   *
   * @param current The current scope.
   * @param idx The index of the array or list.
   * @return An object at the given location or null.
   */
  @SuppressWarnings("rawtypes")
  private Object resolveArrayAccess(final Object current, final String idx) {
    // It is a number, check if the current value is a index base object.
    int pos = Integer.parseInt(idx);
    try {
      if (current instanceof List) {
        return ((List) current).get(pos);
      } else if (current.getClass().isArray()) {
        return Array.get(current, pos);
      }
    } catch (IndexOutOfBoundsException exception) {
      // Index is outside of range, fallback to null as in handlebar.js
      return null;
    }
    return NULL;
  }

  /**
   * Set the value resolver and propagate it to the extendedContext.
   *
   * @param resolver The value resolver.
   */
  private void setResolver(final ValueResolver resolver) {
    this.resolver = resolver;
    extendedContext.resolver = resolver;
  }

  /**
   * Destroy this context by cleaning up instance attributes.
   */
  public void destroy() {
    model = null;
    if (parent == null) {
      // Root context is the owner of the storage.
      if (data != null) {
        data.clear();
      }
    }
    if (extendedContext != null) {
      extendedContext.destroy();
    }
    parent = null;
    resolver = null;
    data = null;
  }

  @Override
  public String toString() {
    return String.valueOf(model);
  }

  /**
   * Start a new context builder.
   *
   * @param parent The parent context. Required.
   * @param model The model data.
   * @return A new context builder.
   */
  public static Builder newBuilder(final Context parent, final Object model) {
    return new Builder(parent, model);
  }

  /**
   * Start a new context builder.
   *
   * @param model The model data.
   * @return A new context builder.
   */
  public static Builder newBuilder(final Object model) {
    return new Builder(model);
  }

  /**
   * Creates a new child context.
   *
   * @param parent The parent context. Required.
   * @param model The model data.
   * @return A new child context.
   */
  public static Context newContext(final Context parent, final Object model) {
    return newBuilder(parent, model).build();
  }

  /**
   * Creates a new block param context.
   *
   * @param parent The parent context. Required.
   * @param names A list of names to set in the block param context.
   * @param values A list of values to set in the block param context.
   * @return A new block param context.
   */
  public static Context newBlockParamContext(final Context parent, final List<String> names,
      final List<Object> values) {
    Map<String, Object> hash = new HashMap<String, Object>();
    for (int i = 0; i < names.size(); i++) {
      hash.put(names.get(i), values.get(i));
    }
    return new BlockParam(parent, hash);
  }

  /**
   * Creates a new root context.
   *
   * @param model The model data.
   * @return A new root context.
   */
  public static Context newContext(final Object model) {
    return newBuilder(model).build();
  }

  /**
   * Creates a new child context.
   *
   * @param model A model/data.
   * @return A new context.
   */
  private Context newChild(final Object model) {
    Context child = newChildContext(model);
    child.extendedContext = new Context(new HashMap<String, Object>());
    child.parent = this;
    child.data = this.data;
    return child;
  }

  /**
   * Creates an empty/default context.
   *
   * @param model A model/data.
   * @return A new context.
   */
  protected Context newChildContext(final Object model) {
    return new Context(model);
  }

}
