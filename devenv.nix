{ pkgs, ... }: {
  languages.java.enable = true;
  languages.clojure.enable = true;
  languages.javascript.enable = true;
  languages.javascript.package = pkgs.nodejs;
  packages = with pkgs; [ babashka ];
}
