/*
 * Copyright 2013-2014 Sergey Ignatov, Alexander Zolotov, Mihai Toader
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.goide.psi;

import com.goide.GoFileType;
import com.goide.GoLanguage;
import com.goide.GoTypes;
import com.goide.psi.impl.GoElementFactory;
import com.goide.stubs.GoConstSpecStub;
import com.goide.stubs.GoFileStub;
import com.goide.stubs.GoVarSpecStub;
import com.goide.stubs.types.*;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.roots.impl.ProjectFileIndexImpl;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubTree;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.ArrayFactory;
import com.intellij.util.PathUtil;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class GoFile extends PsiFileBase {
  private static final String MAIN_FUNCTION_NAME = "main";

  public GoFile(@NotNull FileViewProvider viewProvider) {
    super(viewProvider, GoLanguage.INSTANCE);
  }

  @Nullable
  public String getFullPackageName() {
    VirtualFile virtualFile = getOriginalFile().getVirtualFile();
    VirtualFile root = ProjectFileIndexImpl.SERVICE.getInstance(getProject()).getSourceRootForFile(virtualFile);
    if (root != null) {
      String fullPackageName = FileUtil.getRelativePath(root.getPath(), virtualFile.getPath(), '/');
      if (fullPackageName != null && StringUtil.containsChar(fullPackageName, '/')) {
        return PathUtil.getParentPath(fullPackageName);
      }
    }
    else {
      final String path = PathUtil.getParentPath(virtualFile.getPath());
      final int srcFolder = path.lastIndexOf("/src/");
      if (srcFolder > -1) {
        return StringUtil.nullize(path.substring(srcFolder + 5));
      }
    }
    return null;
  }

  @Nullable
  public GoPackageClause getPackage() {
    GoFileStub stub = getStub();
    if (stub != null) {
      String name = stub.getPackageName();
      return GoElementFactory.createPackageClause(stub.getProject(), name);
    }
    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<GoPackageClause>() {
      @Override
      public Result<GoPackageClause> compute() {
        List<GoPackageClause> packageClauses = calc(new Condition<PsiElement>() {
          @Override
          public boolean value(PsiElement element) {
            return GoPackageClause.class.isInstance(element);
          }
        });
        return Result.create(ContainerUtil.getFirstItem(packageClauses), GoFile.this);
      }
    });
  }

  @Nullable
  public GoImportList getImportList() {
    return findChildByClass(GoImportList.class);
  }

  @NotNull
  public List<GoFunctionDeclaration> getFunctions() {
    StubElement<GoFile> stub = getStub();
    if (stub != null) return getChildrenByType(stub, GoTypes.FUNCTION_DECLARATION, GoFunctionDeclarationStubElementType.ARRAY_FACTORY);

    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<List<GoFunctionDeclaration>>() {
      @Override
      public Result<List<GoFunctionDeclaration>> compute() {
        List<GoFunctionDeclaration> calc = calc(new Condition<PsiElement>() {
          @Override
          public boolean value(PsiElement element) {
            return GoFunctionDeclaration.class.isInstance(element);
          }
        });
        return Result.create(calc, GoFile.this);
      }
    });
  }

  @NotNull
  public List<GoMethodDeclaration> getMethods() {
    StubElement<GoFile> stub = getStub();
    if (stub != null) return getChildrenByType(stub, GoTypes.METHOD_DECLARATION, GoMethodDeclarationStubElementType.ARRAY_FACTORY);

    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<List<GoMethodDeclaration>>() {
      @Override
      public Result<List<GoMethodDeclaration>> compute() {
        List<GoMethodDeclaration> calc = calc(new Condition<PsiElement>() {
          @Override
          public boolean value(PsiElement element) {
            return GoMethodDeclaration.class.isInstance(element);
          }
        });
        return Result.create(calc, GoFile.this);
      }
    });
  }

  @NotNull
  public List<GoTypeSpec> getTypes() {
    StubElement<GoFile> stub = getStub();
    if (stub != null) return getChildrenByType(stub, GoTypes.TYPE_SPEC, GoTypeSpecStubElementType.ARRAY_FACTORY);

    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<List<GoTypeSpec>>() {
      @Override
      public Result<List<GoTypeSpec>> compute() {
        return Result.create(calcTypes(), GoFile.this);
      }
    });
  }

  @NotNull
  public List<GoImportSpec> getImports() {
    StubElement<GoFile> stub = getStub();
    if (stub != null) return ContainerUtil.emptyList();
    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<List<GoImportSpec>>() {
      @Override
      public Result<List<GoImportSpec>> compute() {
        return Result.create(calcImports(), GoFile.this);
      }
    });
  }
  
  public GoImportSpec addImport(String path, String alias) {
    GoImportList importList = getImportList();
    if (importList != null) {
      return importList.addImport(path, alias);
    }
    return null;
  }

  /**
   * @return map like { full package name -> import spec } for file
   */
  @NotNull
  public Map<String, GoImportSpec> getImportedPackagesMap() {
    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<Map<String, GoImportSpec>>() {
      @Nullable
      @Override
      public Result<Map<String, GoImportSpec>> compute() {
        Map<String, GoImportSpec> map = ContainerUtil.newHashMap();
        for (GoImportSpec spec : getImports()) {
          map.put(spec.getImportString().getPath(), spec);
        }
        return Result.create(map, GoFile.this);
      }
    });
  }

  @NotNull
  public MultiMap<String, GoImportSpec> getImportMap() {
    MultiMap<String, GoImportSpec> map = MultiMap.create();
    for (GoImportSpec spec : getImports()) {
      String alias = spec.getAlias();
      if (alias != null) {
        map.putValue(alias, spec);
        continue;
      }
      if (spec.getDot() != null) {
        map.putValue(".", spec);
        continue;
      }
      GoImportString string = spec.getImportString();
      PsiDirectory dir = string.resolve();
      final Collection<String> packagesInDirectory = getAllPackagesInDirectory(dir);
      if (!packagesInDirectory.isEmpty()) {
        for (String packageNames : packagesInDirectory) {
          if (!StringUtil.isEmpty(packageNames)) {
            map.putValue(packageNames, spec);
          }
        }
      }
      else {
        String key = spec.getLocalPackageName(false);
        if (!StringUtil.isEmpty(key)) {
          map.putValue(key, spec);
        }
      }
    }
    return map;
  }

  @NotNull
  public List<GoVarDefinition> getVars() {
    StubElement<GoFile> stub = getStub();
    if (stub != null) {
      List<GoVarDefinition> result = ContainerUtil.newArrayList();
      List<GoVarSpec> varSpecs = getChildrenByType(stub, GoTypes.VAR_SPEC, GoVarSpecStubElementType.ARRAY_FACTORY);
      for (GoVarSpec spec : varSpecs) {
        GoVarSpecStub specStub = spec.getStub();
        if (specStub == null) continue;
        result.addAll(getChildrenByType(specStub, GoTypes.VAR_DEFINITION, GoVarDefinitionStubElementType.ARRAY_FACTORY));
      }
      return result;
    }
    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<List<GoVarDefinition>>() {
      @Override
      public Result<List<GoVarDefinition>> compute() {
        return Result.create(calcVars(), GoFile.this);
      }
    });
  }

  @NotNull
  public List<GoConstDefinition> getConstants() {
    StubElement<GoFile> stub = getStub();
    if (stub != null) {
      List<GoConstDefinition> result = ContainerUtil.newArrayList();
      List<GoConstSpec> constSpecs = getChildrenByType(stub, GoTypes.CONST_SPEC, GoConstSpecStubElementType.ARRAY_FACTORY);
      for (GoConstSpec spec : constSpecs) {
        GoConstSpecStub specStub = spec.getStub();
        if (specStub == null) continue;
        result.addAll(getChildrenByType(specStub, GoTypes.CONST_DEFINITION, GoConstDefinitionStubElementType.ARRAY_FACTORY));
      }
      return result;
    }
    return CachedValuesManager.getCachedValue(this, new CachedValueProvider<List<GoConstDefinition>>() {
      @Override
      public Result<List<GoConstDefinition>> compute() {
        return Result.create(calcConsts(), GoFile.this);
      }
    });
  }

  private static Collection<String> getAllPackagesInDirectory(@Nullable final PsiDirectory dir) {
    if (dir == null) {
      return Collections.emptyList();
    }
    return CachedValuesManager.getCachedValue(dir, new CachedValueProvider<Collection<String>>() {
      @Nullable
      @Override
      public Result<Collection<String>> compute() {
        Collection<String> set = ContainerUtil.newLinkedHashSet();
        for (PsiFile file : dir.getFiles()) {
          if (file instanceof GoFile) {
            String name = ((GoFile)file).getPackageName();
            if (name != null && !"main".equals(name)) {
              set.add(StringUtil.trimEnd(name, "_test"));
            }
          }
        }
        return Result.create(set, dir);
      }
    });
  }

  @NotNull
  private List<GoTypeSpec> calcTypes() {
    final List<GoTypeSpec> result = ContainerUtil.newArrayList();
    processChildrenDummyAware(this, new Processor<PsiElement>() {
      @Override
      public boolean process(PsiElement e) {
        if (e instanceof GoTypeDeclaration) {
          for (GoTypeSpec spec : ((GoTypeDeclaration)e).getTypeSpecList()) {
            result.add(spec);
          }
        }
        return true;
      }
    });
    return result;
  }

  @NotNull
  private List<GoImportSpec> calcImports() {
    List<GoImportSpec> result = ContainerUtil.newArrayList();
    GoImportList list = getImportList();
    if (list == null) return ContainerUtil.emptyList();
    for (GoImportDeclaration declaration : list.getImportDeclarationList()) {
      for (GoImportSpec spec : declaration.getImportSpecList()) {
        result.add(spec);
      }
    }
    return result;
  }

  @NotNull
  private List<GoVarDefinition> calcVars() {
    final List<GoVarDefinition> result = ContainerUtil.newArrayList();
    processChildrenDummyAware(this, new Processor<PsiElement>() {
      @Override
      public boolean process(PsiElement e) {
        if (e instanceof GoVarDeclaration) {
          for (GoVarSpec spec : ((GoVarDeclaration)e).getVarSpecList()) {
            for (GoVarDefinition def : spec.getVarDefinitionList()) {
              result.add(def);
            }
          }
        }
        return true;
      }
    });
    return result;
  }

  @NotNull
  private List<GoConstDefinition> calcConsts() {
    final List<GoConstDefinition> result = ContainerUtil.newArrayList();
    processChildrenDummyAware(this, new Processor<PsiElement>() {
      @Override
      public boolean process(PsiElement e) {
        if (e instanceof GoConstDeclaration) {
          for (GoConstSpec spec : ((GoConstDeclaration)e).getConstSpecList()) {
            for (GoConstDefinition def : spec.getConstDefinitionList()) {
              result.add(def);
            }
          }
        }
        return true;
      }
    });
    return result;
  }

  @NotNull
  private <T extends PsiElement> List<T> calc(@NotNull final Condition<PsiElement> condition) {
    final List<T> result = ContainerUtil.newArrayList();
    processChildrenDummyAware(this, new Processor<PsiElement>() {
      @Override
      public boolean process(PsiElement e) {
        if (condition.value(e)) {
          //noinspection unchecked
          result.add((T)e);
        }
        return true;
      }
    });
    return result;
  }

  @NotNull
  @Override
  public FileType getFileType() {
    return GoFileType.INSTANCE;
  }

  @Nullable
  public GoFunctionDeclaration findMainFunction() { // todo create a map for faster search
    List<GoFunctionDeclaration> functions = getFunctions();
    for (GoFunctionDeclaration function : functions) {
      if (MAIN_FUNCTION_NAME.equals(function.getName())) {
        return function;
      }
    }
    return null;
  }

  @Nullable
  public String getPackageName() {
    GoFileStub stub = getStub();
    if (stub != null) return stub.getPackageName();

    GoPackageClause packageClause = getPackage();
    if (packageClause != null) {
      PsiElement packageIdentifier = packageClause.getIdentifier();
      if (packageIdentifier != null) {
        return packageIdentifier.getText().trim();
      }
    }
    return null;
  }

  @Nullable
  @Override
  public GoFileStub getStub() {
    //noinspection unchecked
    return (GoFileStub)super.getStub();
  }

  private static boolean processChildrenDummyAware(@NotNull GoFile file, @NotNull final Processor<PsiElement> processor) {
    StubTree stubTree = file.getStubTree();
    if (stubTree != null) {
      List<StubElement<?>> plainList = stubTree.getPlainList();
      for (StubElement<?> stubElement : plainList) {
        PsiElement psi = stubElement.getPsi();
        if (!processor.process(psi)) return false;
      }
      return true;
    }
    return new Processor<PsiElement>() {
      @Override
      public boolean process(@NotNull PsiElement psiElement) {
        for (PsiElement child = psiElement.getFirstChild(); child != null; child = child.getNextSibling()) {
          if (child instanceof GeneratedParserUtilBase.DummyBlock) {
            if (!process(child)) return false;
          }
          else if (!processor.process(child)) return false;
        }
        return true;
      }
    }.process(file);
  }

  @NotNull
  private static <E extends PsiElement> List<E> getChildrenByType(@NotNull StubElement<? extends PsiElement> stub,
                                                                  IElementType elementType,
                                                                  ArrayFactory<E> f) {
    return Arrays.asList(stub.getChildrenByType(elementType, f));
  }
}