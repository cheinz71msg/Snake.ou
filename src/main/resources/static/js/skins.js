/**
 * Liste der wählbaren Schlangen-Skins. Muss inhaltlich mit
 * GameEngine.SKINS (Backend) übereinstimmen, damit die ID vom Server
 * erkannt wird. Jeder Skin hat eine Kopf- ("primary") und eine
 * Schwanzfarbe ("secondary") für einen Farbverlauf am Körper.
 */
const SKIN_LIST = [
    {id: "fire", name: "Feuer", primary: "#ff5555", secondary: "#ffb347"},
    {id: "ocean", name: "Ozean", primary: "#55aaff", secondary: "#55ffe0"},
    {id: "forest", name: "Wald", primary: "#55ff88", secondary: "#2e8b57"},
    {id: "galaxy", name: "Galaxie", primary: "#aa55ff", secondary: "#ff55dd"},
    {id: "gold", name: "Gold", primary: "#ffdd55", secondary: "#ff9955"},
    {id: "neon", name: "Neon-Pink", primary: "#ff55dd", secondary: "#aa55ff"},
    {id: "ice", name: "Eis", primary: "#66ddff", secondary: "#ffffff"},
    {id: "lava", name: "Lava", primary: "#ff3300", secondary: "#330000"},
    {id: "toxic", name: "Giftgrün", primary: "#c6ff55", secondary: "#55ff88"},
    {id: "sunset", name: "Sonnenuntergang", primary: "#ff9955", secondary: "#ff5599"},
];
